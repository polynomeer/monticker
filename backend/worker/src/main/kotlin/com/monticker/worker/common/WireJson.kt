package com.monticker.worker.common

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 프로세스 경계를 넘는 JSON(Kafka 메시지)을 worker가 읽고 쓰는 매퍼. 예전엔 프로듀서·컨슈머마다 같은 설정의
 * `ObjectMapper().findAndRegisterModules()`를 각자 만들었다 — 설정은 그대로 두고 한 곳으로 모았다.
 *
 * 이 매퍼가 만드는 형식이 곧 배포 중 구버전·신버전이 서로 주고받는 형식이다. `WireFormatGoldenTest`가 지금 형식
 * (예: `Instant`를 초.나노초 숫자로)을 고정한다 — Jackson 3 전환(docs/dependency-upgrade-plan.md B4-6)은 이 테스트가
 * 초록인 채로 해야 한다. ObjectMapper는 설정 후 스레드 안전하다.
 */
object WireJson {
    val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
}
