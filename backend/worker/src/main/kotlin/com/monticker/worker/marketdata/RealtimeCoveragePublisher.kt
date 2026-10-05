package com.monticker.worker.marketdata

import com.monticker.worker.kis.KisCoverageProvider
import com.monticker.worker.kis.KisWebSocketClient
import com.monticker.worker.toss.TossCoverageProvider
import com.monticker.worker.toss.TossExecutionTickSubscriber
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * ADR-060 — 이 worker가 실시세(KIS·Toss)를 구독하는 종목 집합을 `realtime_price_coverage`에 공표한다. api는 이 테이블로
 * 조건부 주문 생성을 허용하고(커버리지 밖이면 409), 조회 응답에 실시세 상태를 싣는다. 커버리지의 단일 진실 소스는 여기(worker)다 —
 * api가 같은 계산을 복제하면 둘이 어긋나는 순간 사고가 난다(ADR-055와 같은 이유).
 *
 * 기동 직후와 60초마다 집합 전체를 한 트랜잭션으로 덮어쓴다: 집합의 행은 `published_at = now()`로 upsert하고, 그보다 오래된 행(이번에
 * 공표하지 않은 종목)은 지운다 — `now()`는 트랜잭션 안에서 상수다. 실시세가 꺼져 있으면 빈 집합이라 테이블이 비워진다.
 * 공표가 끊기면(worker 다운) api는 5분 지난 행을 인정하지 않는다.
 *
 * 시세 생산자 역할에서만 돈다(MarketTickScheduler와 같은 조건) — 역할마다 설정이 다르면 서로 덮어쓴다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}'.matches('market|all')")
class RealtimeCoveragePublisher(
    private val jdbc: JdbcTemplate,
    private val kisCoverage: KisCoverageProvider,
    private val tossCoverage: TossCoverageProvider,
    transactionManager: PlatformTransactionManager,
    private val kisWebSocket: KisWebSocketClient,
    // Toss 구독기는 ingestion.source에 toss가 있을 때만 빈이 생긴다.
    private val tossSubscriber: ObjectProvider<TossExecutionTickSubscriber>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    @Volatile private var lastPublishedSize = -1

    @Scheduled(fixedDelay = 60_000, initialDelay = 0)
    fun publish() {
        // 선언된 집합이 아니라 **지금 연결이 살아 있는** 집합을 공표한다(브랜치 리뷰에서 발견). 웹소켓이 끊기면 그 종목들은 실시세가
        // 없는데, 선언 집합을 그대로 내보내면 api는 커버리지로 믿고 — 같은 시장 전체가 동시에 조용해져 끊김 판정(STALE)도 "장 마감"과
        // 구별되지 않는다. 연결 상태는 worker만 안다. 막 기동해 아직 연결 전이면 잠깐 비어 있다(조건부 주문 생성이 그동안 거부된다).
        val kis = if (kisWebSocket.isConnected) kisCoverage.coveredStockIds else emptySet()
        val toss = tossSubscriber.ifAvailable?.connectedStockIds()?.intersect(tossCoverage.coveredStockIds) ?: emptySet()
        // 정렬 — 여러 레플리카가 동시에 공표해도 같은 순서로 행을 잡아 교착하지 않는다.
        val rows = (kis.map { it to "KIS" } + toss.map { it to "TOSS" }).sortedBy { it.first }
        runCatching {
            tx.executeWithoutResult {
                // 공표자끼리 직렬화한다(k8s base는 role=all worker와 worker-market을 함께 띄운다) — 교착·덮어쓰기 경합 대신 차례로.
                jdbc.query("SELECT pg_advisory_xact_lock(?)", { _ -> }, ADVISORY_KEY)
                if (rows.isNotEmpty()) {
                    jdbc.batchUpdate(
                        """
                        INSERT INTO realtime_price_coverage (stock_id, source, published_at) VALUES (?, ?, now())
                        ON CONFLICT (stock_id) DO UPDATE SET source = EXCLUDED.source, published_at = EXCLUDED.published_at
                        """.trimIndent(),
                        rows.map { (id, source) -> arrayOf<Any>(id, source) },
                    )
                }
                jdbc.update("DELETE FROM realtime_price_coverage WHERE published_at < now()")
            }
        }.onSuccess {
            if (rows.size != lastPublishedSize) {
                log.info("[Coverage] 실시세 커버리지 공표: KIS {}종목, TOSS {}종목(연결된 것만)", kis.size, toss.size)
                lastPublishedSize = rows.size
            }
        }.onFailure { log.warn("[Coverage] 실시세 커버리지 공표 실패 — api는 5분 뒤부터 커버리지를 모름으로 본다: {}", it.message) }
    }

    companion object {
        /** pg_advisory_xact_lock 키 — 커버리지 공표 전용. */
        private const val ADVISORY_KEY = 60_000_001L
    }
}
