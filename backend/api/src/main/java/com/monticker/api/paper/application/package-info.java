/**
 * paper 모듈의 서비스 계층 — 다른 모듈은 paper.infrastructure(리포지토리)를 직접 참조하지 않고
 * 이 패키지의 서비스를 통해서만 paper 데이터에 접근한다.
 */
@org.springframework.modulith.NamedInterface("api")
package com.monticker.api.paper.application;
