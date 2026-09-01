/**
 * batch.brokerage.BrokerageSettlementJobConfig의 RepositoryItemReader가 직접 참조하는 리포지토리.
 * Spring Batch의 RepositoryItemReader는 Spring Data 리포지토리 빈을 직접 요구하므로
 * 서비스 계층으로 감쌀 수 없는 프레임워크 제약에 의한 예외적 노출이다.
 */
@org.springframework.modulith.NamedInterface("batch")
package com.monticker.api.brokerage.infrastructure;
