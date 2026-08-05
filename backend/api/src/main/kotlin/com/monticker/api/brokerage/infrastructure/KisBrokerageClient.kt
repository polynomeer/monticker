package com.monticker.api.brokerage.infrastructure

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
 * 참조:
 *   - https://apiportal.koreainvestment.com/apiservice/apiservice-domestic-stock
 *   - TR_ID: TTTC0802U(현금 매수), TTTC0801U(현금 매도), TTTC0803U(정정/취소)
 */
@Component
@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false")
class KisBrokerageClient(
    @Value("\${app.kis.base-url}") private val baseUrl: String,
) : BrokerageClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    // ── 토큰 발급 ─────────────────────────────────────────────────────────────

    override fun issueToken(appKey: String, appSecret: String): BrokerageToken {
        return try {
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
        } catch (e: RestClientException) {
            log.error("[KIS] 토큰 발급 실패: {}", e.message)
            throw IllegalStateException("KIS 토큰 발급 실패: ${e.message}", e)
        }
    }

    // ── 주문 ───────────────────────────────────────────────────────────────────

    override fun submitOrder(token: BrokerageToken, request: BrokerageOrderRequest): BrokerageOrderResult {
        return try {
            // TR_ID: 현금 매수 TTTC0802U, 현금 매도 TTTC0801U
            val trId = if (request.side == "BUY") "TTTC0802U" else "TTTC0801U"

            val body = mapOf(
                "CANO"      to "",           // 계좌번호 앞 8자리 (BrokerageService에서 account 정보로 채움)
                "ACNT_PRDT_CD" to "01",     // 계좌상품코드
                "PDNO"      to request.symbol,
                "ORD_DVSN"  to if (request.orderType == "MARKET") "01" else "00", // 01=시장가, 00=지정가
                "ORD_QTY"   to request.quantity.toString(),
                "ORD_UNPR"  to (request.limitPrice?.toString() ?: "0"),
            )

            val resp = restClient.post()
                .uri("/uapi/domestic-stock/v1/trading/order-cash")
                .header("authorization", "Bearer ${token.accessToken}")
                .header("tr_id", trId)
                .header("custtype", "P")
                .body(body)
                .retrieve()
                .body(KisOrderResponse::class.java)

            if (resp?.rtCd == "0") {
                val pgOrderId = resp.output?.odno ?: "UNKNOWN"
                log.info("[KIS] 주문 접수: trId={} odno={}", trId, pgOrderId)
                BrokerageOrderResult(pgOrderId = pgOrderId, status = "SUBMITTED")
            } else {
                log.warn("[KIS] 주문 거부: rtCd={} msg={}", resp?.rtCd, resp?.msg1)
                BrokerageOrderResult(pgOrderId = "REJECTED_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = resp?.msg1)
            }
        } catch (e: RestClientException) {
            log.error("[KIS] 주문 실패: {}", e.message)
            BrokerageOrderResult(pgOrderId = "ERR_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = e.message)
        }
    }

    // ── 주문 조회 ─────────────────────────────────────────────────────────────

    override fun getOrderStatus(token: BrokerageToken, pgOrderId: String): BrokerageOrderStatus {
        return try {
            val resp = restClient.get()
                .uri("/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl?CANO=&ACNT_PRDT_CD=01&CTX_AREA_FK100=&CTX_AREA_NK100=&INQR_DVSN_1=0&INQR_DVSN_2=0")
                .header("authorization", "Bearer ${token.accessToken}")
                .header("tr_id", "TTTC8036R")
                .retrieve()
                .body(KisOrderStatusResponse::class.java)

            val item = resp?.output1?.firstOrNull { it.odno == pgOrderId }
                ?: return BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)

            val status = when (item.ordSttsDvsnName) {
                "전량체결" -> "FILLED"
                "일부체결" -> "PARTIALLY_FILLED"
                "취소"     -> "CANCELLED"
                else        -> "SUBMITTED"
            }
            BrokerageOrderStatus(
                pgOrderId    = pgOrderId,
                status       = status,
                filledQty    = item.tot_ccld_qty?.toIntOrNull() ?: 0,
                avgFillPrice = item.avg_prvs?.toBigDecimalOrNull(),
            )
        } catch (e: RestClientException) {
            log.error("[KIS] 주문 조회 실패: {}", e.message)
            BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
        }
    }

    // ── 정산 내역 조회 ────────────────────────────────────────────────────────

    override fun getSettlements(token: BrokerageToken, date: LocalDate): List<BrokerageSettlementItem> {
        return try {
            val dateStr = date.format(DATE_FMT)
            val resp = restClient.get()
                .uri("/uapi/domestic-stock/v1/trading/inquire-account-balance?CANO=&ACNT_PRDT_CD=01&AFHR_FLPR_YN=N&OFL_YN=&INQR_DVSN=02&UNPR_DVSN=01&FUND_STTL_ICLD_YN=N&FNCG_AMT_AUTO_RDPT_YN=N&PRCS_DVSN=00&CTX_AREA_FK100=&CTX_AREA_NK100=")
                .header("authorization", "Bearer ${token.accessToken}")
                .header("tr_id", "TTTC8434R")
                .retrieve()
                .body(KisSettlementResponse::class.java)

            resp?.output1?.filter { it.sttl_dt == dateStr }?.map { item ->
                BrokerageSettlementItem(
                    pgOrderId  = item.odno ?: "",
                    symbol     = item.pdno ?: "",
                    side       = if ((item.sll_buy_dvsn_cd ?: "02") == "02") "BUY" else "SELL",
                    quantity   = item.ccld_qty?.toIntOrNull() ?: 0,
                    fillPrice  = item.ccld_unpr?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    fee        = item.bfee?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    tax        = item.tl_tax?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    settleDate = date,
                )
            } ?: emptyList()
        } catch (e: RestClientException) {
            log.error("[KIS] 정산 조회 실패: {}", e.message)
            emptyList()
        }
    }

    // ── 잔고 조회 ─────────────────────────────────────────────────────────────

    override fun getBalance(token: BrokerageToken): BrokerageBalance {
        return try {
            val resp = restClient.get()
                .uri("/uapi/domestic-stock/v1/trading/inquire-balance?CANO=&ACNT_PRDT_CD=01&AFHR_FLPR_YN=N&OFL_YN=&INQR_DVSN=02&UNPR_DVSN=01&FUND_STTL_ICLD_YN=N&FNCG_AMT_AUTO_RDPT_YN=N&PRCS_DVSN=00&CTX_AREA_FK100=&CTX_AREA_NK100=")
                .header("authorization", "Bearer ${token.accessToken}")
                .header("tr_id", "TTTC8434R")
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

    private data class KisOrderOutput(val odno: String?)

    private data class KisOrderStatusResponse(
        val output1: List<KisOrderStatusItem>?,
    )

    private data class KisOrderStatusItem(
        val odno: String?,
        val ord_sttsDvsnName: String?,
        val tot_ccld_qty: String?,
        val avg_prvs: String?,
    ) {
        val ordSttsDvsnName: String? get() = ord_sttsDvsnName
    }

    private data class KisSettlementResponse(
        val output1: List<KisSettlementItem>?,
    )

    private data class KisSettlementItem(
        val odno: String?,
        val pdno: String?,
        val sll_buy_dvsn_cd: String?,
        val ccld_qty: String?,
        val ccld_unpr: String?,
        val bfee: String?,
        val tl_tax: String?,
        val sttl_dt: String?,
    )

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
