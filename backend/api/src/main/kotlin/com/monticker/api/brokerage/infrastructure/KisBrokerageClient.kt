package com.monticker.api.brokerage.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
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
    @Value("\${app.kis.base-url}") private val baseUrl: String,
    cbRegistry: CircuitBreakerRegistry,
) : BrokerageClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("kis")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    // 모의투자 서버(openapivts)인지에 따라 TR_ID 접두사(실전 T/C, 모의 V)가 달라진다.
    private val isVirtual = baseUrl.contains("vts")

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
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
            throw IllegalStateException("KIS API 장애로 서킷브레이커가 열려 있습니다. 잠시 후 다시 시도하세요.", e)
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

    override fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest): BrokerageOrderResult {
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

                if (resp?.rtCd == "0") {
                    val pgOrderId = resp.output?.odno ?: "UNKNOWN"
                    log.info("[KIS] 주문 접수: trId={} odno={} orgno={}", trId, pgOrderId, resp.output?.krxFwdgOrdOrgno)
                    BrokerageOrderResult(pgOrderId = pgOrderId, status = "SUBMITTED", brokerOrderRef = resp.output?.krxFwdgOrdOrgno)
                } else {
                    log.warn("[KIS] 주문 거부: rtCd={} msg={}", resp?.rtCd, resp?.msg1)
                    BrokerageOrderResult(pgOrderId = "REJECTED_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = resp?.msg1)
                }
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 주문 제출 건너뜀")
            BrokerageOrderResult(pgOrderId = "CB_OPEN_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = "KIS API 서킷브레이커 OPEN")
        } catch (e: RestClientException) {
            log.error("[KIS] 주문 실패: {}", e.message)
            BrokerageOrderResult(pgOrderId = "ERR_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = e.message)
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
                    val status = when {
                        rejectedQty > 0            -> "REJECTED"
                        filledQty > 0 && remainQty == 0 -> "FILLED"
                        filledQty > 0 && remainQty > 0   -> "PARTIALLY_FILLED"
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
            log.warn("[CircuitBreaker:kis] 요청 차단됨 — 잔고 조회 건너뜀")
            BrokerageBalance(BigDecimal.ZERO, BigDecimal.ZERO, emptyList())
        } catch (e: RestClientException) {
            log.error("[KIS] 잔고 조회 실패: {}", e.message)
            BrokerageBalance(BigDecimal.ZERO, BigDecimal.ZERO, emptyList())
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
