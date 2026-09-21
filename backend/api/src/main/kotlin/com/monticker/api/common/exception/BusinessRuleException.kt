package com.monticker.api.common.exception

/**
 * docs/validation-hardening-plan.md — 전역 예외 분류 개선. GlobalExceptionHandler가
 * IllegalStateException을 한국어 키워드("불가"/"없음" 등) 문자열 매칭으로 409/500 구분해서,
 * 매직 키워드가 없는 새 메시지는 정상적인 사용자 오류인데도 조용히 500으로 샌다
 * (ReconnectRequiredException.kt가 이 휴리스틱이 실제로 오작동했던 사고를 기록한 전례와 같은
 * 종류). 메시지 내용과 무관하게 항상 409로 매핑돼야 하는 비즈니스 규칙 위반은 이 타입을 쓴다.
 */
class BusinessRuleException(message: String) : RuntimeException(message)
