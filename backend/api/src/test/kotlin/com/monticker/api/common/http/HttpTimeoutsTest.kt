package com.monticker.api.common.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import com.sun.net.httpserver.HttpServer

/**
 * resilience-plan §B1 / P0-2 — 타임아웃이 실제로 발동하는지를 서버를 띄워 확인한다.
 * 설정값만 검사하면 "설정은 했는데 factory가 안 붙은" 회귀를 못 잡는다.
 */
class HttpTimeoutsTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun startHangingServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 응답을 영원히 보내지 않는 핸들러 — "죽지 않고 느린" 외부 API를 흉내낸다
        server.createContext("/hang") { exchange ->
            Thread.sleep(5_000)
            exchange.sendResponseHeaders(200, 0); exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `requestFactory로 만든 RestClient는 read 타임아웃에 걸려 매달리지 않는다`() {
        val client = RestClient.builder()
            .baseUrl("http://127.0.0.1:${server.address.port}")
            .requestFactory(HttpTimeouts.requestFactory(Duration.ofMillis(300)))
            .build()

        val started = System.nanoTime()
        assertThatThrownBy { client.get().uri("/hang").retrieve().body(String::class.java) }
            .isInstanceOf(ResourceAccessException::class.java)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(elapsedMs).isLessThan(3_000)   // 5초 핸들러를 기다리지 않았다
    }

    @Test
    fun `브로커 read 타임아웃은 CB slow-call 임계(3s)보다 길다`() {
        // 느림이 타임아웃보다 먼저 집계돼야 브레이커가 스레드 고갈보다 먼저 열린다 (CircuitBreakerConfiguration 주석)
        assertThat(HttpTimeouts.BROKER_READ).isGreaterThan(Duration.ofSeconds(3))
        assertThat(HttpTimeouts.CONNECT).isLessThanOrEqualTo(Duration.ofSeconds(3))
    }
}
