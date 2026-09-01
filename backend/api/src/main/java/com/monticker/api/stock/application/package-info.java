/**
 * stock 모듈의 조회 서비스 계층 — 다른 모듈은 stock.infrastructure(리포지토리)를 직접 참조하지 않고
 * 이 패키지의 서비스를 통해서만 종목 데이터를 조회한다.
 */
@org.springframework.modulith.NamedInterface("api")
package com.monticker.api.stock.application;
