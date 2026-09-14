package com.monticker.api.common.http

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
    fun requestFactory(read: Duration): JdkClientHttpRequestFactory =
        JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT).build()
        ).apply { setReadTimeout(read) }
}
