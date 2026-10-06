package com.monticker.api.common.http

import org.springframework.http.HttpMethod
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

/**
 * 외부 HTTP 호출의 타임아웃을 한 곳에서 정한다 (resilience-plan §B1, P0-2).
 *
 * 이 파일이 생기기 전에는 KIS/Toss 브로커·Toss PG의 RestClient에 requestFactory가 없어
 * read 타임아웃이 무제한이었다. 외부가 죽지 않고 "느려지면" 호출이 반환되지 않으니
 * 서킷브레이커는 실패로 집계하지 못하고, Tomcat 스레드가 하나씩 응답을 기다리며 쌓여
 * 주문과 무관한 요청까지 죽는다. 실제 사용자 자금이 걸린 경로에서 이건 허용되지 않는다.
 *
 * 값의 근거:
 *  - BROKER 5s   : 증권사 주문 API는 정상 시 수백 ms. 5초를 넘기면 이미 장애이고,
 *                  CircuitBreakerConfiguration의 slowCallDurationThreshold(3s)가 그 전에 잡는다.
 *  - PAYMENT 10s : PG 승인은 카드사 경유로 브로커보다 느릴 수 있다. 단 무제한은 아니다.
 *  - BATCH 15s   : 배치(캔들 백필)는 사용자 요청 스레드가 아니므로 여유를 주되 상한은 둔다.
 *  - INTERNAL 5s : 내부 webhook 등.
 */
object HttpTimeouts {
    val CONNECT: Duration = Duration.ofSeconds(3)
    val BROKER_READ: Duration = Duration.ofSeconds(5)
    val PAYMENT_READ: Duration = Duration.ofSeconds(10)
    val BATCH_READ: Duration = Duration.ofSeconds(15)
    val INTERNAL_READ: Duration = Duration.ofSeconds(5)

    /** RestClient / RestTemplate 용. 호출부마다 factory를 직접 만들면 하나씩 빠뜨린다. */
    fun requestFactory(read: Duration): ClientHttpRequestFactory =
        TimeoutAsIoFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT).build()
            ).apply { setReadTimeout(read) }
        )

    /**
     * Spring 6.2의 JDK 클라이언트는 read 타임아웃 때 응답 future를 취소하는데, 타이밍에 따라 `CancellationException`이 그대로
     * 새어 나온다(CI에서 간헐 재현). IOException이 아니면 RestClient가 `ResourceAccessException`으로 감싸지 않아, 브로커 호출을
     * "통신 실패"로 분류하는 쪽(결과 불명 판정, 서킷브레이커 집계)이 예상하지 못한 예외를 받는다. 타임아웃은 항상 IOException으로 낸다.
     */
    internal class TimeoutAsIoFactory(private val delegate: ClientHttpRequestFactory) : ClientHttpRequestFactory {
        override fun createRequest(uri: URI, httpMethod: HttpMethod): ClientHttpRequest {
            val request = delegate.createRequest(uri, httpMethod)
            return object : ClientHttpRequest by request {
                override fun execute(): ClientHttpResponse =
                    try {
                        request.execute()
                    } catch (e: CancellationException) {
                        throw HttpTimeoutException("응답 대기 시간 초과: $httpMethod $uri").apply { initCause(e) }
                    }
            }
        }
    }
}
