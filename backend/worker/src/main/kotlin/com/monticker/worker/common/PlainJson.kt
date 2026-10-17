package com.monticker.worker.common

import tools.jackson.databind.json.JsonMapper

/**
 * 외부 API 요청·응답(KIS·Toss·DART·Naver·KRX·Expo)과 Redis·DB에 넣는 JSON을 다루는 매퍼(Jackson 3, B4-6).
 * 예전엔 클래스마다 `ObjectMapper()`(모듈 없음, Jackson 2 기본값)를 만들었다 — 같은 기본값을 `configureForJackson2()`로 되살린다.
 *
 * 트리 모델을 읽을 때 Jackson 2와 다른 점(ExternalApiParsingGoldenTest가 고정한다):
 * - `asLong()`·`asInt()` 등은 숫자가 아닌 문자열·빈 문자열에서 예외를 던진다 — 기본값 판(`asLong(0)`)을 쓴다(Jackson 2와 같다).
 * - JSON `null`의 문자열은 `asString("")` → `""`다. Jackson 2의 `asText()`는 문자열 `"null"`을 돌려줘 DB에 "null"이 들어갔다.
 * - 3.1의 `JsonNode.map(Function)` 멤버가 Kotlin `map` 확장을 가린다 — 원소를 돌 때는 `values().map { }`.
 */
object PlainJson {
    val mapper: JsonMapper = JsonMapper.builder().configureForJackson2().build()
}
