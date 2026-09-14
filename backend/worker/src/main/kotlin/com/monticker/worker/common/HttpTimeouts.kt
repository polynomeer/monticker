package com.monticker.worker.common

import java.time.Duration

/**
 * 외부 HTTP 호출 타임아웃 (resilience-plan §B, P0-2).
 *
 * java.net.http.HttpClient의 connectTimeout은 연결까지만 막는다 — 응답이 안 오는 "느린 외부"는
 * HttpRequest.Builder.timeout()이 없으면 무기한 기다린다. 워커의 클라이언트 8곳이 전부 이 상태였다.
 * 워커는 사용자 요청 스레드가 아니지만, 스케줄러 스레드가 매달리면 수집이 조용히 멈추고
 * @DistributedLock TTL이 만료돼 다른 인스턴스가 중복 실행하는 경로가 생긴다.
 */
object HttpTimeouts {
    val CONNECT: Duration = Duration.ofSeconds(3)
    /** KIS 시세·토큰, Toss 토큰 — 정상 수백 ms. CB slowCallDurationThreshold(3s)보다 길게. */
    val BROKER_READ: Duration = Duration.ofSeconds(5)
    /** 뉴스·공시·KRX 목록 — 외부 공개 API, 응답이 크다. */
    val COLLECTOR_READ: Duration = Duration.ofSeconds(10)
    /** Expo Push — 배치 발송. */
    val PUSH_READ: Duration = Duration.ofSeconds(10)
}
