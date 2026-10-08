package com.monticker.worker.newsalert

import com.monticker.worker.newsalert.NewsAlertRules.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** ADR-100 — 신선도·중요도·시간당 상한의 경계. 시각은 KST 벽시계로 만들어 JVM 시간대(CI는 UTC)와 무관하게 한다. */
class NewsAlertRulesTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(kst).toInstant()

    @Test
    fun `news is fresh up to 6 hours after publication, inclusive`() {
        val now = at(2026, 10, 9, 12)
        assertThat(NewsAlertRules.fresh(NewsAlertKind.NEWS, at(2026, 10, 9, 6), now)).isTrue()
        assertThat(NewsAlertRules.fresh(NewsAlertKind.NEWS, at(2026, 10, 9, 5, 59), now)).isFalse()
        assertThat(NewsAlertRules.fresh(NewsAlertKind.NEWS, at(2026, 10, 9, 11, 55), now)).isTrue()
        // 발행 시각이 조금 미래(시계 차이)여도 지난 기사가 아니다
        assertThat(NewsAlertRules.fresh(NewsAlertKind.NEWS, at(2026, 10, 9, 12, 5), now)).isTrue()
    }

    @Test
    fun `a backfilled article from yesterday is never notified`() {
        assertThat(NewsAlertRules.fresh(NewsAlertKind.NEWS, at(2026, 10, 8, 9), at(2026, 10, 9, 9))).isFalse()
    }

    @Test
    fun `disclosure is fresh only on its KST receipt date`() {
        val receipt = at(2026, 10, 9, 0)   // DisclosureCollector: 접수일 KST 0시
        assertThat(NewsAlertRules.fresh(NewsAlertKind.DISCLOSURE, receipt, at(2026, 10, 9, 0, 5))).isTrue()
        assertThat(NewsAlertRules.fresh(NewsAlertKind.DISCLOSURE, receipt, at(2026, 10, 9, 23, 59))).isTrue()
        assertThat(NewsAlertRules.fresh(NewsAlertKind.DISCLOSURE, receipt, at(2026, 10, 10, 0, 0))).isFalse()
        // KST 오전 8시 = UTC 전날 23시 — UTC 날짜로 비교하면 어제 공시를 오늘 것으로 볼 수 있다
        assertThat(NewsAlertRules.fresh(NewsAlertKind.DISCLOSURE, at(2026, 10, 8, 0), at(2026, 10, 9, 8))).isFalse()
    }

    @Test
    fun `only disclosures at or above importance 70 qualify, news always does`() {
        assertThat(NewsAlertRules.importanceQualifies(NewsAlertKind.DISCLOSURE, 70)).isTrue()
        assertThat(NewsAlertRules.importanceQualifies(NewsAlertKind.DISCLOSURE, 69)).isFalse()
        assertThat(NewsAlertRules.importanceQualifies(NewsAlertKind.DISCLOSURE, null)).isFalse()
        assertThat(NewsAlertRules.importanceQualifies(NewsAlertKind.NEWS, null)).isTrue()
    }

    @Test
    fun `hourly caps - notify up to 5, then history only, news history stops at 20`() {
        assertThat(NewsAlertRules.decide(NewsAlertKind.NEWS, recorded = 0, notified = 0)).isEqualTo(Decision.NOTIFY)
        assertThat(NewsAlertRules.decide(NewsAlertKind.NEWS, recorded = 4, notified = 4)).isEqualTo(Decision.NOTIFY)
        assertThat(NewsAlertRules.decide(NewsAlertKind.NEWS, recorded = 5, notified = 5)).isEqualTo(Decision.RECORD_ONLY)
        assertThat(NewsAlertRules.decide(NewsAlertKind.NEWS, recorded = 19, notified = 5)).isEqualTo(Decision.RECORD_ONLY)
        assertThat(NewsAlertRules.decide(NewsAlertKind.NEWS, recorded = 20, notified = 5)).isEqualTo(Decision.SKIP)
    }

    @Test
    fun `disclosures share the notify cap but are never dropped from history`() {
        assertThat(NewsAlertRules.decide(NewsAlertKind.DISCLOSURE, recorded = 3, notified = 3)).isEqualTo(Decision.NOTIFY)
        assertThat(NewsAlertRules.decide(NewsAlertKind.DISCLOSURE, recorded = 30, notified = 5)).isEqualTo(Decision.RECORD_ONLY)
    }
}
