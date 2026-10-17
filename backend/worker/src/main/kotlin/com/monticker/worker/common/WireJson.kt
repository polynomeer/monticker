package com.monticker.worker.common

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * 프로세스 경계를 넘는 JSON(Kafka 메시지)을 worker가 읽고 쓰는 매퍼(Jackson 3, B4-6).
 *
 * 이 매퍼가 만드는 형식이 곧 배포 중 구버전·신버전이 서로 주고받는 형식이다. `WireFormatGoldenTest`가 지금 형식
 * (예: `Instant`를 초.나노초 숫자로)을 고정한다. Jackson 3 기본값은 그 형식과 다르므로(날짜를 ISO 문자열로, 속성 정렬 등)
 * `configureForJackson2()`로 Jackson 2(`ObjectMapper().findAndRegisterModules()`) 시절 기본값을 되살린다.
 *
 * 모르는 필드는 **모든 메시지에서** 무시한다 — 생산자가 먼저 배포돼 필드가 늘어도 소비자가 DLT로 보내지 않게.
 * Jackson 2 시절엔 UserNotifyKafkaConsumer가 이 공유 매퍼를 `configure()`로 **바꿔서**, 그 컨슈머가 뜨는 역할에서만
 * 다른 메시지까지 무시가 켜졌다(뜨지 않는 역할에선 실패). Jackson 3 매퍼는 불변이라 그런 일이 없고, 여기서 명시한다.
 * JsonMapper는 불변이고 스레드 안전하다.
 */
object WireJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .configureForJackson2()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
}
