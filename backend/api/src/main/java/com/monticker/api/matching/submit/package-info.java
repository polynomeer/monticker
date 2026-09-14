/**
 * ADR-047: 사용자 요청 핸들러(paper 파사드)가 주문을 제출하는 유일한 동기 진입점.
 * "matching은 이벤트로만 통신한다"의 의도된 예외 — 누가 쓸 수 있는지는 각 모듈의 allowedDependencies가 정한다.
 * 자동화 경로(ai 등)는 이 인터페이스를 나열할 수 없다(ADR-036 유지).
 */
@org.springframework.modulith.NamedInterface("submit")
package com.monticker.api.matching.submit;
