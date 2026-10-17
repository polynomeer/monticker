package com.monticker.api.batch

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Spring Batch 6은 `@EnableBatchProcessing`만으로는 저장소가 메모리(Resourceless)다 — 정산·결제 대조·청소 잡의 실행
 * 이력이 BATCH_* 테이블(V17)에 남지 않고, 재시작·중복 실행 판정도 프로세스마다 따로 된다. JDBC 저장소를 명시한다(B4-5).
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
@EnableScheduling
class BatchConfig
