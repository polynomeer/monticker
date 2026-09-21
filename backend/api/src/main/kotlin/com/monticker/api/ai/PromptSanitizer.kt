package com.monticker.api.ai

/**
 * LLM 프롬프트에 보간되는 외부 텍스트(뉴스/이벤트 제목)의 프롬프트 인젝션 표면을 줄인다
 * (docs/security-review.md M1). 꺾쇠괄호를 유사 문자로 치환해 프롬프트가 쓰는
 * `<untrusted_data>` 같은 구분 태그를 원문 안의 내용으로 조기에 닫을 수 없게 하고, 길이를
 * 제한해 과도하게 긴 텍스트로 지시문을 밀어내는 것도 막는다. 자연어 지시문 자체를 걸러내는
 * 완전한 방어는 아니다 — enum 강제·사람 승인(OrderProposalService)이나 사실 기반 요약
 * 지시(StockSummaryService) 같은 다른 계층과 함께 쓰는 심층 방어다.
 */
fun sanitizeForPrompt(text: String): String =
    text.replace('<', '‹').replace('>', '›').take(300)
