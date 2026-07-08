/**
 * 주문 체결 모듈.
 * 다른 모듈과의 통신은 OrderFilledEvent, OrderCancelledEvent를 통한 이벤트 방식만 허용.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common"}
)
package com.monticker.api.matching;
