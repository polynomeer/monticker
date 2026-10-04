package com.monticker.api.brokerage.infrastructure

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException

/**
 * ADR-056 — 주문 제출 중 난 예외가 "증권사에 접수되지 않았음이 확실한가"를 판정한다.
 *
 * KIS·Toss 클라이언트가 같은 규칙을 쓰도록 한 곳에 둔다. 예전엔 둘 다 `RestClientException`을 전부
 * REJECTED로 바꿔, 응답만 유실되고 실제로는 체결된 주문이 "거부"로 보였다 — 사용자가 다시 누르면 이중 주문이다.
 *
 * 규칙: 요청이 나가지 않았다는 증거가 있을 때만 REJECTED. 그 밖에는 전부 INDETERMINATE.
 */
object SubmitFailureClassifier {

    fun classify(e: Throwable): BrokerageOrderResult = when {
        // 서킷 OPEN — 호출 자체를 하지 않았다
        e is CallNotPermittedException -> BrokerageOrderResult.rejected("증권사 API 서킷브레이커 OPEN — 주문 미전송")

        // 4xx — 증권사가 요청을 읽고 거절했다. 408(요청 타임아웃)만 예외: 처리 중이었을 수 있다.
        e is HttpClientErrorException && e.statusCode.value() != 408 ->
            BrokerageOrderResult.rejected("증권사 거부 (${e.statusCode.value()}): ${e.responseBodyAsString.take(300)}")

        // 5xx — 증권사 내부 오류. 접수 여부를 모른다(Toss 스펙: "주문 처리 중 일시적 오류").
        e is HttpServerErrorException ->
            BrokerageOrderResult.indeterminate("증권사 서버 오류 (${e.statusCode.value()}) — 접수 여부 확인 중")

        // 연결 전에 실패 — 요청 바이트가 나가지 않았다. HttpConnectTimeoutException은 HttpTimeoutException의
        // 하위형이라 아래 일반 타임아웃보다 먼저 봐야 한다.
        causedBy<HttpConnectTimeoutException>(e) || causedBy<ConnectException>(e) || causedBy<UnknownHostException>(e) ->
            BrokerageOrderResult.rejected("증권사 연결 실패 — 주문 미전송: ${e.message}")

        // 읽기 타임아웃을 포함한 나머지 — 요청은 나갔을 수 있다.
        else -> BrokerageOrderResult.indeterminate("증권사 응답 없음 — 접수 여부 확인 중: ${e.message}")
    }

    private inline fun <reified T : Throwable> causedBy(e: Throwable): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }.any { it is T }
}
