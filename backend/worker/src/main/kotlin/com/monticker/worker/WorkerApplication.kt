package com.monticker.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// @EnableScheduling을 여기 둔다 — MarketTickScheduler처럼 worker.role/ingestion.source
// 조건에 따라 빈 등록 자체가 스킵될 수 있는 클래스에 두면, 그 조건이 거짓인 배포
// (worker.role=event|alert, 또는 role=all + ingestion.source=kafka)에서는
// ScheduledAnnotationBeanPostProcessor가 아예 등록되지 않아 모든 @Scheduled 컬렉터
// (backfillOnStartup, StockMasterCollector, StockFundamentalsCollector 등)가 조용히 죽는다.
@EnableScheduling
@SpringBootApplication
class WorkerApplication

fun main(args: Array<String>) {
	runApplication<WorkerApplication>(*args)
}
