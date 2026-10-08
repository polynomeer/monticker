package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.common.exception.ExternalServiceUnavailableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** 증권사 API 호출 종류. 응답에 그대로 나간다 — 고정된 이름만. */
enum class BrokerCallOperation { SUBMIT_ORDER, CANCEL_ORDER, GET_ORDER_STATUS, GET_SETTLEMENTS, FIND_ORDERS, GET_BALANCE }

/**
 * 증권사 호출 실패를 **고정 코드**로 바꾼다. 원문 메시지(예외 message, 증권사 응답 본문)는 계좌번호·주문번호·키 일부가 섞일 수
 * 있어 저장하지도 내보내지도 않는다. 예외 타입과 HTTP 상태만 본다.
 */
enum class BrokerErrorCode {
    TIMEOUT, NETWORK, CIRCUIT_OPEN, BROKER_UNAVAILABLE, AUTH_FAILED, RATE_LIMITED, BROKER_4XX, BROKER_5XX,
    /** 주문 제출 결과를 모른다(ADR-056 INDETERMINATE) */
    SUBMIT_INDETERMINATE,
    /** 증권사가 주문을 거부했다(ADR-056 REJECTED) */
    ORDER_REJECTED,
    /** 조회 실패(findOrders가 null) */
    LOOKUP_FAILED,
    UNKNOWN;

    companion object {
        fun classify(e: Throwable): BrokerErrorCode {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 8) {
                when (t) {
                    is CallNotPermittedException -> return CIRCUIT_OPEN
                    is SocketTimeoutException -> return TIMEOUT
                    is HttpStatusCodeException -> return when (t.statusCode.value()) {
                        401, 403 -> AUTH_FAILED
                        429 -> RATE_LIMITED
                        in 500..599 -> BROKER_5XX
                        else -> BROKER_4XX
                    }
                }
                t = t.cause
                depth++
            }
            // 원인 사슬에 구체적인 것이 없으면 바깥 타입으로
            return when (e) {
                is ResourceAccessException -> NETWORK
                is ExternalServiceUnavailableException -> BROKER_UNAVAILABLE
                else -> UNKNOWN
            }
        }
    }
}

/** 한 계좌의 증권사 API 상태 — 마지막 호출 지연과 마지막 오류. 값은 이 api 인스턴스가 관측한 것이다. */
data class BrokerCallHealth(
    val lastLatencyMs: Long,
    val lastCallAt: Instant,
    val lastOperation: BrokerCallOperation,
    val lastSuccessAt: Instant?,
    val lastErrorCode: BrokerErrorCode?,
    val lastErrorAt: Instant?,
    val lastErrorOperation: BrokerCallOperation?,
)

/**
 * 계좌별 증권사 호출 지연·오류를 **메모리에만** 보관한다(테이블 아님).
 *
 * - 주문 제출 경로(ADR-056 tx1 → 증권사 호출 → tx2)에 DB 쓰기를 하나도 더하지 않는다. 쓰기가 실패하면 새로운 실패 모드가 실주문
 *   경로에 생긴다 — 진단용 값 때문에 감수할 위험이 아니다.
 * - 진단 값이다. 재시작하면 비고, 레플리카마다 자기가 처리한 호출만 안다. 화면은 "이 서버가 마지막으로 본 값"으로 표시하고,
 *   값이 없으면 `—`다. 운영 추이는 기존 메트릭(브로커 호출 카운터·서킷브레이커)이 맡는다.
 * - 키는 (증권사, 앱키, 계좌번호)의 SHA-256이다. 원문 키·계좌번호를 맵 키로 들고 있지 않고, 같은 계좌번호를 다른 사용자가 쓰는
 *   Mock 모드에서도 앱키가 다르면 섞이지 않는다.
 * - 크기 상한 [MAX_ENTRIES]. 넘으면 [RETENTION]보다 오래된 항목부터 버린다.
 */
@Component
class BrokerCallHealthTracker {
    /** 테스트에서 시각을 고정할 때만 바꾼다. */
    internal var clock: Clock = Clock.systemUTC()

    private val byAccount = ConcurrentHashMap<String, BrokerCallHealth>()

    fun record(key: String, op: BrokerCallOperation, latency: Duration, error: BrokerErrorCode?) {
        val now = clock.instant()
        byAccount.compute(key) { _, prev ->
            BrokerCallHealth(
                lastLatencyMs = latency.toMillis().coerceAtLeast(0),
                lastCallAt = now,
                lastOperation = op,
                lastSuccessAt = if (error == null) now else prev?.lastSuccessAt,
                lastErrorCode = error ?: prev?.lastErrorCode,
                lastErrorAt = if (error != null) now else prev?.lastErrorAt,
                lastErrorOperation = if (error != null) op else prev?.lastErrorOperation,
            )
        }
        if (byAccount.size > MAX_ENTRIES) evict(now)
    }

    fun get(provider: BrokerageProvider, appKey: String?, accountNumber: String): BrokerCallHealth? =
        appKey?.let { byAccount[keyOf(provider, it, accountNumber)] }

    private fun evict(now: Instant) {
        val cutoff = now.minus(RETENTION)
        byAccount.entries.removeIf { it.value.lastCallAt.isBefore(cutoff) }
        if (byAccount.size > MAX_ENTRIES) byAccount.clear()   // 하루 안에 1만 계좌를 넘는 규모가 되면 이 구조를 다시 본다
    }

    internal fun size() = byAccount.size

    companion object {
        const val MAX_ENTRIES = 10_000
        val RETENTION: Duration = Duration.ofHours(24)

        fun keyOf(provider: BrokerageProvider, appKey: String, accountNumber: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${provider.name}\u0000$appKey\u0000$accountNumber".toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}

/**
 * [BrokerageClient] 데코레이터 — 호출 시간과 실패 코드를 [BrokerCallHealthTracker]에 남기고, **그 밖에는 아무것도 바꾸지 않는다**:
 * 같은 인자로 위임하고, 결과를 그대로 돌려주고, 예외를 그대로 다시 던진다. 기록이 실패해도 호출 결과에 영향을 주지 않는다.
 * [movesRealMoney]도 위임한다(인터페이스 기본값 true로 떨어지면 Mock이 실계좌로 취급돼 ADR-055/060 게이트 동작이 바뀐다).
 *
 * 토큰 발급·계좌 참조 조회(연동 시점)는 앱키만 있고 계좌번호가 없어 계좌 키를 만들 수 없으므로 재지 않는다. 인증 상태는 기존
 * `tokenValid`가 보여 준다.
 */
class InstrumentedBrokerageClient(
    private val delegate: BrokerageClient,
    private val provider: BrokerageProvider,
    private val tracker: BrokerCallHealthTracker,
) : BrokerageClient {

    override val movesRealMoney: Boolean get() = delegate.movesRealMoney

    override fun issueToken(appKey: String, appSecret: String): BrokerageToken = delegate.issueToken(appKey, appSecret)

    override fun resolveAccountRef(token: BrokerageToken, accountNumber: String): String? =
        delegate.resolveAccountRef(token, accountNumber)

    override fun submitOrder(credentials: BrokerageCredentials, request: BrokerageOrderRequest, clientOrderId: String): BrokerageOrderResult =
        timed(credentials, BrokerCallOperation.SUBMIT_ORDER, { r ->
            when (r.outcome) {
                SubmitOutcome.ACCEPTED -> null
                SubmitOutcome.REJECTED -> BrokerErrorCode.ORDER_REJECTED
                SubmitOutcome.INDETERMINATE -> BrokerErrorCode.SUBMIT_INDETERMINATE
            }
        }) { delegate.submitOrder(credentials, request, clientOrderId) }

    override fun cancelOrder(credentials: BrokerageCredentials, pgOrderId: String, brokerOrderRef: String?): BrokerageCancelResult =
        timed(credentials, BrokerCallOperation.CANCEL_ORDER) { delegate.cancelOrder(credentials, pgOrderId, brokerOrderRef) }

    override fun getOrderStatus(credentials: BrokerageCredentials, pgOrderId: String): BrokerageOrderStatus =
        timed(credentials, BrokerCallOperation.GET_ORDER_STATUS) { delegate.getOrderStatus(credentials, pgOrderId) }

    override fun getSettlements(credentials: BrokerageCredentials, date: java.time.LocalDate): List<BrokerageSettlementItem> =
        timed(credentials, BrokerCallOperation.GET_SETTLEMENTS) { delegate.getSettlements(credentials, date) }

    override fun findOrders(credentials: BrokerageCredentials, date: java.time.LocalDate, symbol: String, side: String): List<BrokerOrderSnapshot>? =
        timed(credentials, BrokerCallOperation.FIND_ORDERS, { r -> if (r == null) BrokerErrorCode.LOOKUP_FAILED else null }) {
            delegate.findOrders(credentials, date, symbol, side)
        }

    override fun getBalance(credentials: BrokerageCredentials): BrokerageBalance =
        timed(credentials, BrokerCallOperation.GET_BALANCE) { delegate.getBalance(credentials) }

    private inline fun <T> timed(
        credentials: BrokerageCredentials,
        op: BrokerCallOperation,
        noinline errorOf: (T) -> BrokerErrorCode? = { null },
        call: () -> T,
    ): T {
        val started = System.nanoTime()
        val result = try {
            call()
        } catch (e: Throwable) {
            safeRecord(credentials, op, started) { BrokerErrorCode.classify(e) }
            throw e
        }
        safeRecord(credentials, op, started) { errorOf(result) }
        return result
    }

    private inline fun safeRecord(credentials: BrokerageCredentials, op: BrokerCallOperation, started: Long, error: () -> BrokerErrorCode?) {
        try {
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            tracker.record(BrokerCallHealthTracker.keyOf(provider, credentials.appKey, credentials.accountNumber), op, elapsed, error())
        } catch (_: Exception) {
            // 진단 기록 실패는 호출 결과를 바꾸지 않는다
        }
    }
}
