/**
 * ADR-016: settlement 모듈(CreatorEarningsService)이 전략 구독 결제를 처리할 때 사용하는
 * PG 연동 계약(PgClient, PaymentRequest, PaymentResult).
 */
@org.springframework.modulith.NamedInterface("pg")
package com.monticker.api.subscription.infrastructure.pg;
