/**
 * 공용 커널(shared kernel) 모듈 — 다른 어떤 비즈니스 모듈에도 의존하지 않는다.
 * OPEN으로 지정해 하위 패키지(aop, domain, tracing, config 등)를 전부 공개 API로 취급한다.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = {}
)
package com.monticker.api.common;
