package com.monticker.api.brokerage.infrastructure

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 토스증권 Open API 실 구현체.
 *
 * 활성화 조건: app.brokerage.mock.enabled=false (프로덕션 기본값)
 *
 * 필요 환경변수:
 *   TOSS_BASE_URL   — https://openapi.tossinvest.com (모의투자 서버 없음 — KIS와 달리 처음부터 실계좌)
 *   TOSS_APP_KEY    — client_id (사용자별 BYOK, .env에 넣지 않음)
 *   TOSS_APP_SECRET — client_secret
 *
 * ADR-026 — Toss가 공개한 정식 OpenAPI 3.0 스펙(openapi.tossinvest.com/openapi-docs/latest/openapi.json,
 * v1.2.14)으로 엔드포인트/스키마를 직접 검증했다. KIS와 달리 모의투자 서버가 없어, 이 클라이언트는
 * 실계좌로 첫 검증을 하기 전까지 "스펙상 맞음"만 보장한다.
 *
 * KIS와의 핵심 차이 — Toss는 계좌번호(accountNo) 문자열만으로 호출할 수 없고, 별도 조회로 얻는
 * accountSeq(정수)를 매 호출 X-Tossinvest-Account 헤더에 실어야 한다(resolveAccountRef 참고).
 */
@Component
@ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false")
class TossBrokerageClient(
    @Value("\${app.toss.base-url}") private val baseUrl: String,
    cbRegistry: CircuitBreakerRegistry,
) : BrokerageClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cb = cbRegistry.circuitBreaker("toss")

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    // ── 토큰 발급 ─────────────────────────────────────────────────────────────

    override fun issueToken(appKey: String, appSecret: String): BrokerageToken {
        return try {
            cb.executeCallable {
                val form = LinkedMultiValueMap<String, String>().apply {
                    add("grant_type", "client_credentials")
                    add("client_id", appKey)
                    add("client_secret", appSecret)
                }

                val resp = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TossTokenResponse::class.java)
                    ?: throw IllegalStateException("Toss 토큰 응답이 없습니다.")

                log.info("[Toss] 토큰 발급 성공: expiresIn={}초", resp.expiresIn)
                BrokerageToken(accessToken = resp.accessToken, expiresIn = resp.expiresIn)
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 토큰 발급 건너뜀")
            throw IllegalStateException("Toss API 장애로 서킷브레이커가 열려 있습니다. 잠시 후 다시 시도하세요.", e)
        } catch (e: RestClientException) {
            log.error("[Toss] 토큰 발급 실패: {}", e.message)
            throw IllegalStateException("Toss 토큰 발급 실패: ${e.message}", e)
        }
    }

    // ── 계좌 참조(accountSeq) 조회 ────────────────────────────────────────────
    //
    // Toss는 계좌번호(accountNo) 문자열만으로 API를 호출할 수 없다 — 모든 계좌/주문 API가
    // 요구하는 X-Tossinvest-Account 헤더 값(accountSeq)은 이 엔드포인트로만 얻을 수 있다.
    // ACCOUNT rate limit 그룹이 초당 1회로 가장 낮으므로, connect() 시점에 한 번만 호출해
    // BrokerageAccount.providerAccountRef에 저장해두고 재사용한다(BrokerageService 참고).

    override fun resolveAccountRef(token: BrokerageToken, accountNumber: String): String? {
        return try {
            cb.executeCallable {
                val resp = restClient.get()
                    .uri("/api/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token.accessToken}")
                    .retrieve()
                    .body(TossAccountsEnvelope::class.java)

                val normalized = accountNumber.filter { it.isDigit() }
                val account = resp?.result?.firstOrNull { it.accountNo?.filter { c -> c.isDigit() } == normalized }
                    ?: throw IllegalStateException("계좌번호를 찾을 수 없습니다: $accountNumber")

                log.info("[Toss] 계좌 조회 성공: accountSeq={}", account.accountSeq)
                account.accountSeq?.toString()
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 계좌 조회 건너뜀")
            throw IllegalStateException("Toss API 장애로 서킷브레이커가 열려 있습니다. 잠시 후 다시 시도하세요.", e)
        } catch (e: RestClientException) {
            log.error("[Toss] 계좌 조회 실패: {}", e.message)
            throw IllegalStateException("Toss 계좌 조회 실패: ${e.message}", e)
        }
    }

    // ── 공통 헤더 ─────────────────────────────────────────────────────────────

    private fun authHeaders(credentials: BrokerageCredentials): Map<String, String> = mapOf(
        HttpHeaders.AUTHORIZATION to "Bearer ${credentials.token.accessToken}",
        "X-Tossinvest-Account" to (credentials.providerAccountRef
            ?: throw IllegalStateException("Toss 계좌 참조(accountSeq)가 없습니다. 재연동이 필요합니다.")),
    )

    // ── 주문 ───────────────────────────────────────────────────────────────────

    override fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest): BrokerageOrderResult {
        return try {
            cb.executeCallable {
                val body = buildMap<String, Any> {
                    // 멱등성 키 — 서버가 자동 생성하지 않는다. 10분간 유효하며, 같은 값으로
                    // 재요청하면 이전 주문 결과를 그대로 재반환한다.
                    put("clientOrderId", UUID.randomUUID().toString())
                    put("symbol", request.symbol)
                    put("side", request.side)
                    put("orderType", request.orderType)
                    put("quantity", request.quantity.toString())
                    if (request.orderType == "LIMIT") {
                        put("price", request.limitPrice?.toString() ?: throw IllegalArgumentException("지정가 주문에는 가격이 필요합니다."))
                    }
                    // 1억원 이상 주문 확인 플래그 — 금액 상한 정책은 이미 사전 리스크 게이트
                    // (RiskLimit)에서 처리하므로, 브로커 측 착오주문 확인은 항상 통과시킨다.
                    put("confirmHighValueOrder", true)
                }

                val resp = restClient.post()
                    .uri("/api/v1/orders")
                    .headers { h -> authHeaders(credentials).forEach { (k, v) -> h.set(k, v) } }
                    .body(body)
                    .retrieve()
                    .body(TossOrderCreateEnvelope::class.java)

                val orderId = resp?.result?.orderId ?: throw IllegalStateException("Toss 주문 응답에 orderId가 없습니다.")
                log.info("[Toss] 주문 접수: orderId={}", orderId)
                BrokerageOrderResult(pgOrderId = orderId, status = "SUBMITTED")
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 주문 제출 건너뜀")
            BrokerageOrderResult(pgOrderId = "CB_OPEN_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = "Toss API 서킷브레이커 OPEN")
        } catch (e: RestClientException) {
            log.error("[Toss] 주문 실패: {}", e.message)
            BrokerageOrderResult(pgOrderId = "ERR_${System.currentTimeMillis()}", status = "REJECTED", rejectReason = e.message)
        }
    }

    // ── 주문 조회 ─────────────────────────────────────────────────────────────
    //
    // Toss의 주문 상태 enum(PENDING/PENDING_CANCEL/PENDING_REPLACE/PARTIAL_FILLED/FILLED/
    // CANCELED/REJECTED/CANCEL_REJECTED/REPLACE_REJECTED/REPLACED)을 내부 4상태로 접는다.
    // 스펙 자체가 "클라이언트는 unknown code를 허용해야 한다"고 명시하므로, 알 수 없는 값은
    // 보수적으로 SUBMITTED(진행 중)로 취급한다.

    override fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus {
        return try {
            cb.executeCallable {
                val resp = restClient.get()
                    .uri("/api/v1/orders/{orderId}", pgOrderId)
                    .headers { h -> authHeaders(credentials).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(TossOrderEnvelope::class.java)

                val order = resp?.result
                val status = when (order?.status) {
                    "FILLED"         -> "FILLED"
                    "PARTIAL_FILLED" -> "PARTIALLY_FILLED"
                    "CANCELED"       -> "CANCELLED"
                    "REJECTED"       -> "REJECTED"
                    else             -> "SUBMITTED"
                }

                BrokerageOrderStatus(
                    pgOrderId    = pgOrderId,
                    status       = status,
                    filledQty    = order?.execution?.filledQuantity?.toBigDecimalOrNull()?.toInt() ?: 0,
                    avgFillPrice = order?.execution?.averageFilledPrice?.toBigDecimalOrNull(),
                )
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 주문 조회 건너뜀")
            BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
        } catch (e: RestClientException) {
            log.error("[Toss] 주문 조회 실패: {}", e.message)
            BrokerageOrderStatus(pgOrderId, "SUBMITTED", 0, null)
        }
    }

    // ── 정산 내역 조회 ────────────────────────────────────────────────────────
    //
    // BrokerageService는 이 메서드를 호출하지 않는다(정산은 체결 시점에 로컬에서 직접 계산한다) —
    // KIS와 마찬가지로 죽은 코드지만 인터페이스 계약이므로 주문 목록 조회로 채워둔다.

    override fun getSettlements(credentials: BrokerageCredentials, date: LocalDate): List<BrokerageSettlementItem> {
        return try {
            cb.executeCallable {
                val resp = restClient.get()
                    .uri { b ->
                        b.path("/api/v1/orders")
                            .queryParam("status", "CLOSED")
                            .queryParam("from", date.toString())
                            .queryParam("to", date.toString())
                            .build()
                    }
                    .headers { h -> authHeaders(credentials).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(TossOrderListEnvelope::class.java)

                resp?.result?.orders
                    ?.filter { (it.execution?.filledQuantity?.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                    ?.map { order ->
                        BrokerageSettlementItem(
                            pgOrderId  = order.orderId ?: "",
                            symbol     = order.symbol ?: "",
                            side       = order.side ?: "",
                            quantity   = order.execution?.filledQuantity?.toBigDecimalOrNull()?.toInt() ?: 0,
                            fillPrice  = order.execution?.averageFilledPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            fee        = order.execution?.commission?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            tax        = order.execution?.tax?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            settleDate = date,
                        )
                    } ?: emptyList()
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 정산 조회 건너뜀")
            emptyList()
        } catch (e: RestClientException) {
            log.error("[Toss] 정산 조회 실패: {}", e.message)
            emptyList()
        }
    }

    // ── 잔고 조회 ─────────────────────────────────────────────────────────────
    //
    // 매수가능금액(현금) + 보유종목을 각각 조회해 합친다. 현재 도메인 모델이 국내(KRW) 단일
    // 통화만 다루므로, holdings 응답 중 KRW 통화 항목만 반영한다(해외주식은 범위 밖).

    override fun getBalance(credentials: BrokerageCredentials): BrokerageBalance {
        return try {
            cb.executeCallable {
                val buyingPower = restClient.get()
                    .uri { b -> b.path("/api/v1/buying-power").queryParam("currency", "KRW").build() }
                    .headers { h -> authHeaders(credentials).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(TossBuyingPowerEnvelope::class.java)

                val holdings = restClient.get()
                    .uri("/api/v1/holdings")
                    .headers { h -> authHeaders(credentials).forEach { (k, v) -> h.set(k, v) } }
                    .retrieve()
                    .body(TossHoldingsEnvelope::class.java)

                val cash = buyingPower?.result?.cashBuyingPower?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val krwHoldingsValue = holdings?.result?.marketValue?.amount?.krw?.toBigDecimalOrNull() ?: BigDecimal.ZERO

                BrokerageBalance(
                    cash           = cash,
                    totalEvaluated = cash.add(krwHoldingsValue),
                    holdings       = holdings?.result?.items
                        ?.filter { it.currency == "KRW" }
                        ?.map { item ->
                            BrokerageHolding(
                                symbol       = item.symbol ?: "",
                                quantity     = item.quantity?.toBigDecimalOrNull()?.toInt() ?: 0,
                                avgPrice     = item.averagePurchasePrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                                currentPrice = item.lastPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            )
                        } ?: emptyList(),
                )
            }
        } catch (e: CallNotPermittedException) {
            log.warn("[CircuitBreaker:toss] 요청 차단됨 — 잔고 조회 건너뜀")
            BrokerageBalance(BigDecimal.ZERO, BigDecimal.ZERO, emptyList())
        } catch (e: RestClientException) {
            log.error("[Toss] 잔고 조회 실패: {}", e.message)
            BrokerageBalance(BigDecimal.ZERO, BigDecimal.ZERO, emptyList())
        }
    }

    // ── Toss API 응답 DTO ─────────────────────────────────────────────────────
    // 모든 성공 응답은 {"result": ...} 로 감싸여 온다(ApiResponse envelope).

    private data class TossTokenResponse(
        val access_token: String,
        val expires_in: Long,
    ) {
        val accessToken: String get() = access_token
        val expiresIn: Long     get() = expires_in
    }

    private data class TossAccountsEnvelope(val result: List<TossAccount>?)
    private data class TossAccount(val accountNo: String?, val accountSeq: Long?)

    private data class TossOrderCreateEnvelope(val result: TossOrderCreateResult?)
    private data class TossOrderCreateResult(val orderId: String?, val clientOrderId: String?)

    private data class TossOrderEnvelope(val result: TossOrder?)
    private data class TossOrderListEnvelope(val result: TossPaginatedOrders?)
    private data class TossPaginatedOrders(val orders: List<TossOrder>?)

    private data class TossOrder(
        val orderId: String?,
        val symbol: String?,
        val side: String?,
        val status: String?,
        val execution: TossExecution?,
    )

    private data class TossExecution(
        val filledQuantity: String?,
        val averageFilledPrice: String?,
        val commission: String?,
        val tax: String?,
    )

    private data class TossBuyingPowerEnvelope(val result: TossBuyingPower?)
    private data class TossBuyingPower(val currency: String?, val cashBuyingPower: String?)

    private data class TossHoldingsEnvelope(val result: TossHoldingsOverview?)
    private data class TossHoldingsOverview(
        val marketValue: TossHoldingsMarketValue?,
        val items: List<TossHoldingItem>?,
    )
    private data class TossHoldingsMarketValue(val amount: TossCurrencyAmount?)
    private data class TossCurrencyAmount(val krw: String?, val usd: String?)
    private data class TossHoldingItem(
        val symbol: String?,
        val currency: String?,
        val quantity: String?,
        val lastPrice: String?,
        val averagePurchasePrice: String?,
    )
}
