package com.monticker.api.common.exception

/**
 * ADR-027 — 저장된 자격증명(appKey/appSecret)으로 토큰 자동 재발급을 시도했지만 실패했을 때
 * 던진다. IllegalStateException을 썼다면 메시지에 "불가"/"없음" 같은 키워드가 없어
 * GlobalExceptionHandler의 비즈니스 규칙 휴리스틱에 안 걸리고 500으로 새 버렸다(라이브
 * 테스트로 실제 확인) — 전용 타입으로 분리해 항상 401로 매핑되게 한다.
 */
class ReconnectRequiredException(message: String) : RuntimeException(message)
