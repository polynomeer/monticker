package com.monticker.api.brokerage.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import com.monticker.api.common.exception.ExternalServiceUnavailableException
import com.monticker.api.common.http.HttpTimeouts
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 한국투자증권 Open API 실 구현체.
 *
 * 활성화 조건: app.brokerage.mock.enabled=false (프로덕션 기본값)
 *
 * 필요 환경변수:
 *   KIS_BASE_URL       — 실거래: https://openapi.koreainvestment.com:9443
 *                        모의: https://openapivts.koreainvestment.com:29443
 *   KIS_APP_KEY        — 앱 키 (발급 후 환경변수로 주입)
 *   KIS_APP_SECRET     — 앱 시크릿
 *
 * ADR-025 — 엔드포인트/TR_ID는 공식 유지보수 중인 오픈소스 참조 구현체
 * (https://github.com/Soju06/python-kis)로 교차 검증했다. 그래도 실제 KIS 계정으로
 * 검증된 적은 없다 — docs/launch-plan.md Phase 6의 모의투자 E2E 실행 전까지는
 * "스펙상 맞을 가능성이 높다"는 뜻이지 "동작 확인됨"이 아니다.
 *
 * 참조:
 *   - https://apiportal.koreainvestment.com/apiservice/apiservice-domestic-stock
 *   - TR_ID: TTTC0802U(현금 매수), TTTC0801U(현금 매도), TTTC0803U(정정/취소)
 *   - TR_ID: TTTC8001R/VTTC8001R(일별주문체결조회, 최근 3개월 이내)
 *   - TR_ID: TTTC8434R/VTTC8434R(잔고조회)
 *
 * cancelOrder() 구현 중 python-kis 교차검증으로 발견 — order-cash/order-rvsecncl(같은
 * 계열) 응답의 output 필드명은 대문자(ODNO, KRX_FWDG_ORD_ORGNO)인데, 기존 코드는 소문자
 * 필드명(odno)으로 매핑하고 있어 Jackson이 항상 null로 바인딩했다(대소문자 구분).
 * 일별체결조회/잔고조회 응답은 실제로 소문자라 그쪽은 문제없다.
 */
@Component
@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false")
class KisBrokerageClient(
    // application.yml의 실제 키는 app.brokerage.kis.base-url 이다. app.kis.base-url 로 잘못 참조돼 있어
    // BROKERAGE_MOCK_ENABLED=false(운영)에서 부팅이 실패했다 — CH-06 실험 1단계에서 발견 (resilience-plan §6.3).
    @Value("\${app.brokerage.kis.base-url}") private val baseUrl: String,
    cbRegistry: CircuitBreakerRegistry,
) : BrokerageClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("kis")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    // 모의투자 서버(openapivts)인지에 따라 TR_ID 접두사(실전 T/C, 모의 V)가 달라진다.
    private val isVirtual = baseUrl.contains("vts")

    // requestFactory 없이 build()하면 read 타임아웃이 무제한이다 — KIS가 느려지면 스레드가 매달린다 (P0-2).
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(HttpTimeouts.requestFactory(HttpTimeouts.BROKER_READ))
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    // ── 토큰 발급 ─────────────────────────────────────────────────────────────

    override fun issueToken(appKey: String, appSecret: String): BrokerageToken {
        return try {
            cb.executeCallable {
                val body = mapOf(
                    "grant_type" to "client_credentials",
                    "appkey"     to appKey,
                    "appsecret"  to appSecret,
                )
                val resp = restClient.post()
                    .uri("/oauth2/tokenP")
                    .body(body)
                    .retrieve()
                    .body(KisTokenResponse::class.java)
                    ?: throw IllegalStateException("KIS 토큰 응답이 없습니다.")

                log.info("[KIS] 토큰 발급 성공: expiresIn={}초", resp.expiresIn)
                BrokerageToken(accessToken = resp.accessToken, expiresIn = resp.expiresIn)
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 토큰 발급 건너뜀")
            throw ExternalServiceUnavailableException("kis", "KIS API 장애로 서킷브레이커가 열려 있습니다. 잠시 후 다시 시도하세요.", e)
        } catch (e: RestClientException) {
            log.error("[KIS] 토큰 발급 실패: {}", e.message)
            throw IllegalStateException("KIS 토큰 발급 실패: ${e.message}", e)
        }
    }

    // ── 공통 헤더/계좌 필드 ──────────────────────────────────────────────────────

    private fun authHeaders(credentials: BrokerageCredentials, trId: String): Map<String, String> = mapOf(
        "authorization" to "Bearer ${credentials.token.accessToken}",
        "appkey"        to credentials.appKey,
        "appsecret"     to credentials.appSecret,
        "tr_id"         to trId,
    )

    /** 계좌번호를 CANO(종합계좌번호, 앞 8자리)/ACNT_PRDT_CD(계좌상품코드, 나머지)로 분리한다. */
    private fun accountFields(accountNumber: String): Pair<String, String> {
        val digits = accountNumber.filter { it.isDigit() }
        return if (digits.length > 8) digits.take(8) to digits.substring(8) else digits to "01"
    }

    private fun dailyOrderTrId(): String = if (isVirtual) "VTTC8001R" else "TTTC8001R"
    private fun balanceTrId(): String = if (isVirtual) "VTTC8434R" else "TTTC8434R"

    // ── 주문 ───────────────────────────────────────────────────────────────────

    // ADR-056 — KIS에는 클라이언트 주문 ID 개념이 없어 clientOrderId는 보내지 않는다(대조는 당일 목록 매칭).
    override fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest, clientOrderId: String): BrokerageOrderResult {
        return try {
            cb.executeCallable {
                // TR_ID: 현금 매수 TTTC0802U, 현금 매도 TTTC0801U
                val trId = if (request.side == "BUY") "TTTC0802U" else "TTTC0801U"
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)

                val body = mapOf(
                    "CANO"      to cano,
                    "ACNT_PRDT_CD" to acntPrdtCd,
                    "PDNO"      to request.symbol,
                    "ORD_DVSN"  to if (request.orderType == "MARKET") "01" else "00", // 01=시장가, 00=지정가
                    "ORD_QTY"   to request.quantity.toString(),
                    "ORD_UNPR"  to (request.limitPrice?.toString() ?: "0"),
                )

                val resp = restClient.post()
                    .uri("/uapi/domestic-stock/v1/trading/order-cash")
                    .headers { h -> authHeaders(credentials, trId).forEach { (k, v) -> h.set(k, v) } }
                    .header("custtype", "P")
                    .body(body)
                    .retrieve()
                    .body(KisOrderResponse::class.java)

                val odno = resp?.output?.odno
                when {
                    resp?.rtCd == "0" && !odno.isNullOrBlank() -> {
                        log.info("[KIS] 주문 접수: trId={} odno={} orgno={}", trId, odno, resp.output?.krxFwdgOrdOrgno)
                        BrokerageOrderResult.accepted(odno, resp.output?.krxFwdgOrdOrgno)
                    }
                    // rt_cd가 명시적으로 실패 — 정상 응답의 거절이다.
                    resp?.rtCd != null && resp.rtCd != "0" -> {
                        log.warn("[KIS] 주문 거부: rtCd={} msg={}", resp.rtCd, resp.msg1)
                        BrokerageOrderResult.rejected(resp.msg1 ?: "증권사 거부")
                    }
                    // 2xx인데 성공 표시는 있고 주문번호가 없거나, 본문이 비었다 — 접수됐을 수 있다. 예전엔 각각
                    // pgOrderId="UNKNOWN" 문자열로 SUBMITTED, 또는 REJECTED로 처리했다.
                    else -> {
                        log.error("[KIS] 주문 응답 불완전: rtCd={} odno={}", resp?.rtCd, odno)
                        BrokerageOrderResult.indeterminate("증권사 응답에 주문번호가 없음 — 접수 여부 확인 중")
                    }
                }
            }
        } catch (e: Exception) {
            SubmitFailureClassifier.classify(e).also {
                log.error("[KIS] 주문 제출 예외 → {}: {}", it.outcome, e.message)
            }
        }
    }

    // ── 주문 취소 ─────────────────────────────────────────────────────────────
    //
    // 정정취소 엔드포인트(order-rvsecncl)는 응답 output의 ODNO/KRX_FWDG_ORD_ORGNO가 대문자다 —
    // 같은 계열인 주문 제출(order-cash) 응답과 동일하게 대문자, 반면 일별체결조회/잔고조회는
    // 소문자다(python-kis 참조 구현체로 교차 검증, KIS API가 엔드포인트별로 대소문자가
    // 다르다). KRX_FWDG_ORD_ORGNO(지점코드)는 계좌번호만으로 계산할 수 없고 원주문 접수
    // 응답에만 있어, submitOrder()가 돌려준 brokerOrderRef를 그대로 받아야 한다.

    override fun cancelOrder(credentials: BrokerageCredentials, pgOrderId: String, brokerOrderRef: String?): BrokerageCancelResult {
        if (brokerOrderRef == null) {
            return BrokerageCancelResult(cancelled = false, reason = "지점코드(KRX_FWDG_ORD_ORGNO)가 없어 취소할 수 없습니다.")
        }
        return try {
            cb.executeCallable {
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)
                val trId = if (isVirtual) "VTTC0803U" else "TTTC0803U"

                val body = mapOf(
                    "CANO"                  to cano,
                    "ACNT_PRDT_CD"          to acntPrdtCd,
                    "KRX_FWDG_ORD_ORGNO"    to brokerOrderRef,
                    "ORGN_ODNO"             to pgOrderId,
                    "ORD_DVSN"              to "00",
                    "RVSE_CNCL_DVSN_CD"     to "02", // 01=정정, 02=취소
                    "ORD_QTY"               to "0",
                    "ORD_UNPR"              to "0",
                    "QTY_ALL_ORD_YN"        to "Y",
                )

                val resp = restClient.post()
                    .uri("/uapi/domestic-stock/v1/trading/order-rvsecncl")
                    .headers { h -> authHeaders(credentials, trId).forEach { (k, v) -> h.set(k, v) } }
                    .header("custtype", "P")
                    .body(body)
                    .retrieve()
                    .body(KisOrderResponse::class.java)

                if (resp?.rtCd == "0") {
                    log.info("[KIS] 주문 취소 접수: orgnOdno={}", pgOrderId)
                    BrokerageCancelResult(cancelled = true)
                } else {
                    log.warn("[KIS] 주문 취소 거부: rtCd={} msg={}", resp?.rtCd, resp?.msg1)
                    BrokerageCancelResult(cancelled = false, reason = resp?.msg1)
                }
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 주문 취소 건너뜀")
            BrokerageCancelResult(cancelled = false, reason = "KIS API 서킷브레이커 OPEN")
        } catch (e: RestClientException) {
            log.error("[KIS] 주문 취소 실패: {}", e.message)
            BrokerageCancelResult(cancelled = false, reason = e.message)
        }
    }

    // ── 주문 조회 ─────────────────────────────────────────────────────────────
    //
    // 이전 구현은 "정정취소가능주문조회"(inquire-psbl-rvsecncl) 엔드포인트를 썼는데, 이건
    // 아직 취소/정정 가능한(=미체결) 주문만 나열한다 — 전량체결된 주문은 이 목록에서
    // 빠지므로 "목록에 없음"을 "제출됨"으로 해석하면 이미 체결된 주문을 계속 대기 중으로
    // 오판한다. 일별체결조회(inquire-daily-ccld)로 교체하고, 존재하지도 않는
    // ord_sttsDvsnName 필드 대신 rjct_qty/rmn_qty/tot_ccld_qty로 상태를 판정한다.

    override fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus {
        return try {
            cb.executeCallable {
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)
                val today = DATE_FMT.format(LocalDate.now())
                val uri = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld" +
                    "?CANO=$cano&ACNT_PRDT_CD=$acntPrdtCd" +
                    "&INQR_STRT_DT=$today&INQR_END_DT=$today" +
                    "&SLL_BUY_DVSN_CD=00&INQR_DVSN=00&PDNO=&CCLD_DVSN=00" +
                    "&ORD_GNO_BRNO=&ODNO=&INQR_DVSN_3=00&INQR_DVSN_1=&CTX_AREA_FK100=&CTX_AREA_NK100="

                val resp = restClient.get()
                    .uri(uri)
                    .headers { h -> authHeaders(credentials, dailyOrderTrId()).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(KisDailyOrderResponse::class.java)

                val item = resp?.output1?.firstOrNull { it.odno == pgOrderId }

                if (item == null) {
                    // 오늘 접수한 주문이 조회에 아직 반영 안 됐을 수도 있어 보수적으로 SUBMITTED 유지.
                    BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
                } else {
                    val rejectedQty = item.rjctQty?.toIntOrNull() ?: 0
                    val filledQty   = item.totCcldQty?.toIntOrNull() ?: 0
                    val remainQty   = item.rmnQty?.toIntOrNull() ?: 0
                    val orderQty    = item.ord_qty?.toIntOrNull() ?: 0
                    // 잔량이 0인데 체결도 0이면 취소된 주문이다(취소 시 원주문의 잔량이 0이 된다). 일부 체결 뒤 잔량이 취소되면
                    // FILLED로 보고 filledQty(< 주문 수량)만 반영한다 — 이전엔 둘 다 SUBMITTED에 머물러 24시간 동기화 창 밖으로
                    // 빠질 때까지 미체결로 남았다(2026-10 리뷰).
                    val status = when {
                        rejectedQty > 0            -> "REJECTED"
                        filledQty > 0 && remainQty == 0 -> "FILLED"
                        filledQty > 0 && remainQty > 0   -> "PARTIALLY_FILLED"
                        orderQty > 0 && remainQty == 0 && (item.cncl_yn == "Y" || item.cncl_yn == null) -> "CANCELLED"
                        else                        -> "SUBMITTED"
                    }
                    BrokerageOrderStatus(
                        pgOrderId    = pgOrderId,
                        status       = status,
                        filledQty    = filledQty,
                        avgFillPrice = item.avgPrvs?.toBigDecimalOrNull(),
                    )
                }
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 주문 조회 건너뜀")
            BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
        } catch (e: RestClientException) {
            log.error("[KIS] 주문 조회 실패: {}", e.message)
            BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
        }
    }

    // ── 당일 주문 목록 (ADR-056 대조용) ───────────────────────────────────────
    //
    // inquire-daily-ccld를 종목(PDNO)·매매구분(SLL_BUY_DVSN_CD 01=매도, 02=매수)으로 좁혀 부른다. 연속조회
    // (CTX_AREA_*)는 아직 따라가지 않는다 — 대조 창이 1분이라 최근 주문만 필요하지만, 정렬 순서는 실계좌로
    // 확인해야 한다(ADR-056 Consequences).

    override fun findOrders(credentials: BrokerageCredentials, date: LocalDate, symbol: String, side: String): List<BrokerOrderSnapshot>? {
        return try {
            cb.executeCallable {
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)
                val day = DATE_FMT.format(date)
                val sideCode = if (side == "SELL") "01" else "02"
                val uri = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld" +
                    "?CANO=$cano&ACNT_PRDT_CD=$acntPrdtCd" +
                    "&INQR_STRT_DT=$day&INQR_END_DT=$day" +
                    "&SLL_BUY_DVSN_CD=$sideCode&INQR_DVSN=00&PDNO=$symbol&CCLD_DVSN=00" +
                    "&ORD_GNO_BRNO=&ODNO=&INQR_DVSN_3=00&INQR_DVSN_1=&CTX_AREA_FK100=&CTX_AREA_NK100="

                val resp = restClient.get()
                    .uri(uri)
                    .headers { h -> authHeaders(credentials, dailyOrderTrId()).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(KisDailyOrderResponse::class.java)

                // 본문이 없거나 rt_cd가 실패면 "조회 실패"다 — 빈 목록("주문 없음")으로 읽으면 안 된다.
                if (resp == null || (resp.rt_cd != null && resp.rt_cd != "0")) {
                    log.warn("[KIS] 당일 주문 조회 실패: rtCd={} msg={}", resp?.rt_cd, resp?.msg1)
                    return@executeCallable null
                }
                // 한 건이라도 해석하지 못하면 "조회 실패"다. 빠뜨리고 넘어가면 실제로 체결된 주문이 목록에서 사라져
                // 2분 뒤 "미접수"로 확정되고, 사용자는 재주문한다 — ADR-056이 막으려는 이중 주문 그 자체다.
                val items = resp.output1 ?: emptyList()
                val snapshots = items.map { it.toSnapshot(side) }
                if (snapshots.any { it == null }) {
                    log.error("[KIS] 당일 주문 응답 해석 실패 — 조회 실패로 취급: {}건 중 {}건", items.size, snapshots.count { it == null })
                    return@executeCallable null
                }
                snapshots.filterNotNull()
            }
        } catch (e: Exception) {
            log.warn("[KIS] 당일 주문 조회 예외: {}", e.message)
            null
        }
    }

    private fun KisDailyOrderItem.toSnapshot(requestedSide: String): BrokerOrderSnapshot? {
        val id = odno?.takeIf { it.isNotBlank() } ?: return null
        val orderedAt = runCatching {
            java.time.LocalDateTime.parse("${ord_dt}${ord_tmd}", DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant()
        }.getOrNull() ?: return null
        return BrokerOrderSnapshot(
            brokerOrderId  = id,
            brokerOrderRef = ord_gno_brno,
            symbol         = pdno ?: "",
            side           = when (sll_buy_dvsn_cd) { "01" -> "SELL"; "02" -> "BUY"; else -> requestedSide },
            quantity       = ord_qty?.toIntOrNull() ?: 0,
            price          = ord_unpr?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
            orderedAt      = orderedAt,
            status         = kisStatus(),
            filledQty      = totCcldQty?.toIntOrNull() ?: 0,
            avgFillPrice   = avgPrvs?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
        )
    }

    private fun KisDailyOrderItem.kisStatus(): String {
        val rejectedQty = rjctQty?.toIntOrNull() ?: 0
        val filledQty   = totCcldQty?.toIntOrNull() ?: 0
        val remainQty   = rmnQty?.toIntOrNull() ?: 0
        return when {
            rejectedQty > 0                  -> "REJECTED"
            filledQty > 0 && remainQty == 0  -> "FILLED"
            filledQty > 0 && remainQty > 0   -> "PARTIALLY_FILLED"
            else                             -> "SUBMITTED"
        }
    }

    // ── 정산 내역 조회 ────────────────────────────────────────────────────────
    //
    // BrokerageService는 현재 이 메서드를 호출하지 않는다(정산은 로컬 brokerage_settlements를
    // 체결 시점에 직접 계산해 채운다) — 죽은 코드지만 인터페이스 계약이므로 같은
    // inquire-daily-ccld 엔드포인트로 맞춰둔다. 이 응답에서 수수료/세금 필드를 신뢰성 있게
    // 확인하지 못해 0으로 둔다.

    override fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem> {
        return try {
            cb.executeCallable {
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)
                val dateStr = date.format(DATE_FMT)
                val uri = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld" +
                    "?CANO=$cano&ACNT_PRDT_CD=$acntPrdtCd" +
                    "&INQR_STRT_DT=$dateStr&INQR_END_DT=$dateStr" +
                    "&SLL_BUY_DVSN_CD=00&INQR_DVSN=00&PDNO=&CCLD_DVSN=01" +
                    "&ORD_GNO_BRNO=&ODNO=&INQR_DVSN_3=00&INQR_DVSN_1=&CTX_AREA_FK100=&CTX_AREA_NK100="

                val resp = restClient.get()
                    .uri(uri)
                    .headers { h -> authHeaders(credentials, dailyOrderTrId()).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(KisDailyOrderResponse::class.java)

                resp?.output1?.filter { (it.totCcldQty?.toIntOrNull() ?: 0) > 0 }?.map { item ->
                    BrokerageSettlementItem(
                        pgOrderId  = item.odno ?: "",
                        symbol     = item.pdno ?: "",
                        side       = if (item.sllBuyDvsnCd == "02") "BUY" else "SELL",
                        quantity   = item.totCcldQty?.toIntOrNull() ?: 0,
                        fillPrice  = item.avgPrvs?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                        fee        = BigDecimal.ZERO, // 확인되지 않은 필드 — ADR-025 참고
                        tax        = BigDecimal.ZERO,
                        settleDate = date,
                    )
                } ?: emptyList()
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 정산 조회 건너뜀")
            emptyList()
        } catch (e: RestClientException) {
            log.error("[KIS] 정산 조회 실패: {}", e.message)
            emptyList()
        }
    }

    // ── 잔고 조회 ─────────────────────────────────────────────────────────────

    override fun getBalance(credentials: BrokerageCredentials): BrokerageBalance {
        return try {
            cb.executeCallable {
                val (cano, acntPrdtCd) = accountFields(credentials.accountNumber)
                val uri = "/uapi/domestic-stock/v1/trading/inquire-balance" +
                    "?CANO=$cano&ACNT_PRDT_CD=$acntPrdtCd" +
                    "&AFHR_FLPR_YN=N&OFL_YN=&INQR_DVSN=02&UNPR_DVSN=01&FUND_STTL_ICLD_YN=N" +
                    "&FNCG_AMT_AUTO_RDPT_YN=N&PRCS_DVSN=00&CTX_AREA_FK100=&CTX_AREA_NK100="

                val resp = restClient.get()
                    .uri(uri)
                    .headers { h -> authHeaders(credentials, balanceTrId()).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(KisBalanceResponse::class.java)

                val summary = resp?.output2?.firstOrNull()
                BrokerageBalance(
                    cash           = summary?.dnca_tot_amt?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    totalEvaluated = summary?.tot_evlu_amt?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    holdings       = resp?.output1?.map { h ->
                        BrokerageHolding(
                            symbol       = h.pdno ?: "",
                            quantity     = h.hldg_qty?.toIntOrNull() ?: 0,
                            avgPrice     = h.pchs_avg_pric?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            currentPrice = h.prpr?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                        )
                    } ?: emptyList(),
                )
            }
        } catch (e: CallNotPermittedException) {
            // CH-06 발견: 여기서 0원 잔고를 돌려주면 증권사 장애 중 사용자에게 "잔고 0원·보유 없음"이 보이고,
            // 리스크 게이트(buildPortfolioSnapshot)와 리밸런싱 미리보기는 빈 포트폴리오를 사실로 믿는다.
            // 조회 불가는 조회 불가로 — 주문 경로와 같은 503이다.
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 잔고 조회 불가")
            throw ExternalServiceUnavailableException("kis", "KIS API 장애로 서킷브레이커가 열려 있습니다. 잔고를 확인할 수 없습니다.", e)
        } catch (e: RestClientException) {
            log.error("[KIS] 잔고 조회 실패: {}", e.message)
            throw ExternalServiceUnavailableException("kis", "KIS 잔고 조회에 실패했습니다. 잠시 후 다시 시도하세요.", e)
        }
    }

    // ── KIS API 응답 DTO ──────────────────────────────────────────────────────

    private data class KisTokenResponse(
        val access_token: String,
        val expires_in: Long,
    ) {
        val accessToken: String get() = access_token
        val expiresIn: Long     get() = expires_in
    }

    private data class KisOrderResponse(
        val rt_cd: String?,
        val msg1: String?,
        val output: KisOrderOutput?,
    ) {
        val rtCd: String? get() = rt_cd
    }

    // 주문 제출/취소(order-cash, order-rvsecncl) 응답의 output은 필드명이 대문자다 —
    // 일별체결조회/잔고조회(아래)는 소문자라 서로 다르다(python-kis로 교차 검증).
    private data class KisOrderOutput(
        @JsonProperty("ODNO") val odno: String?,
        @JsonProperty("KRX_FWDG_ORD_ORGNO") val krxFwdgOrdOrgno: String?,
    )

    private data class KisDailyOrderResponse(
        val rt_cd: String? = null,
        val msg1: String? = null,
        val output1: List<KisDailyOrderItem>?,
    )

    private data class KisDailyOrderItem(
        val odno: String?,
        val pdno: String?,
        val sll_buy_dvsn_cd: String?,
        val ord_qty: String?,
        val tot_ccld_qty: String?,
        val rmn_qty: String?,
        val rjct_qty: String?,
        val avg_prvs: String?,
        // ADR-056 대조용 — 주문일자(yyyyMMdd)·주문시각(HHmmss, KST)·주문단가·주문채번지점번호(취소 시 KRX_FWDG_ORD_ORGNO)
        val ord_dt: String? = null,
        val ord_tmd: String? = null,
        val ord_unpr: String? = null,
        val ord_gno_brno: String? = null,
        val cncl_yn: String? = null,   // 취소 여부
    ) {
        val sllBuyDvsnCd: String? get() = sll_buy_dvsn_cd
        val totCcldQty: String?   get() = tot_ccld_qty
        val rmnQty: String?       get() = rmn_qty
        val rjctQty: String?      get() = rjct_qty
        val avgPrvs: String?      get() = avg_prvs
    }

    private data class KisBalanceResponse(
        val output1: List<KisHoldingItem>?,
        val output2: List<KisBalanceSummary>?,
    )

    private data class KisHoldingItem(
        val pdno: String?,
        val hldg_qty: String?,
        val pchs_avg_pric: String?,
        val prpr: String?,
    )

    private data class KisBalanceSummary(
        val dnca_tot_amt: String?,  // 예수금 총금액
        val tot_evlu_amt: String?,  // 총 평가금액
    )
}
