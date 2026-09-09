/**
 * ADR-037 — 종목 커뮤니티 댓글. 종목 존재 확인·이벤트 태그 검증을 위해 stock/event
 * 모듈을 참조한다. AnthropicClient/AnthropicConfig는 common.config에 있어 ai 모듈에
 * 의존하지 않고 직접 사용한다(보일러플레이트를 작게 중복하는 편이 전이 의존을 늘리는
 * 것보다 낫다는 판단, ADR-037 Decision 참고).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "stock::api", "stock::domain", "event::api", "event::domain"}
)
package com.monticker.api.community;
