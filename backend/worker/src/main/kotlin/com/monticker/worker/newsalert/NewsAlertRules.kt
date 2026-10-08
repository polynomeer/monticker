package com.monticker.worker.newsalert

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * ADR-100 — 관심종목 뉴스·공시 알림의 양을 묶는 규칙. 순수 함수라 단위 테스트로 경계를 고정한다.
 *
 * - **신선도**: 수집은 지난 기사·공시를 함께 가져온다(뉴스 API의 최근 결과, DART의 최근 1일). 뉴스는 발행 [NEWS_MAX_AGE] 이내,
 *   공시는 접수일(KST 달력일)이 오늘인 것만 알린다. 판정은 **팬아웃 시각** 기준이다 — 아웃박스 재전송이 늦게 돌아도 지난 기사를
 *   "지금 일어난 일"처럼 보내지 않는다.
 * - **중요도**: 공시에만 점수가 있다(`stock_events.importance_score`). [DISCLOSURE_MIN_IMPORTANCE] 미만(임원·주요주주 변동, 기타)은 알리지 않는다.
 *   뉴스에는 점수가 없어(감성만) 상한으로만 묶는다.
 * - **사용자별 시간당 상한**(최근 [CAP_WINDOW]): 알림(푸시·이메일)은 [NOTIFY_CAP_PER_HOUR]건까지, 그 뒤는 이력만 남긴다(`CAPPED`).
 *   뉴스 이력은 [RECORD_CAP_PER_HOUR]건까지만 남긴다 — 알림함의 최근 목록을 뉴스가 덮지 않게. 공시는 중요도로 이미 걸러졌고 드물어
 *   이력 상한을 받지 않는다(알림 상한은 받는다).
 */
object NewsAlertRules {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    val NEWS_MAX_AGE: Duration = Duration.ofHours(6)
    const val DISCLOSURE_MIN_IMPORTANCE = 70
    val CAP_WINDOW: Duration = Duration.ofHours(1)
    const val NOTIFY_CAP_PER_HOUR = 5
    const val RECORD_CAP_PER_HOUR = 20

    enum class Decision {
        /** 이력 + 알림(발송 여부·채널·방해 금지 시간은 발송 정책이 정한다) */
        NOTIFY,
        /** 이력만 — 시간당 알림 상한을 넘었다 */
        RECORD_ONLY,
        /** 아무것도 하지 않는다 — 시간당 이력 상한을 넘었다(뉴스만) */
        SKIP,
    }

    fun importanceQualifies(kind: NewsAlertKind, importanceScore: Int?): Boolean = when (kind) {
        NewsAlertKind.NEWS -> true
        NewsAlertKind.DISCLOSURE -> (importanceScore ?: 0) >= DISCLOSURE_MIN_IMPORTANCE
    }

    /** 지금([now]) 알려도 되는 사건인가. 뉴스는 발행 6시간 이내, 공시는 접수일(KST)이 오늘. */
    fun fresh(kind: NewsAlertKind, publishedAt: Instant, now: Instant): Boolean = when (kind) {
        NewsAlertKind.NEWS -> !publishedAt.isBefore(now.minus(NEWS_MAX_AGE))
        NewsAlertKind.DISCLOSURE -> publishedAt.atZone(KST).toLocalDate() == now.atZone(KST).toLocalDate()
    }

    /** 이 사용자가 최근 한 시간에 [recorded]건의 뉴스·공시 이력을 받았고 그중 [notified]건이 알림까지 나갔다면 이번 건은? */
    fun decide(kind: NewsAlertKind, recorded: Int, notified: Int): Decision = when {
        kind == NewsAlertKind.NEWS && recorded >= RECORD_CAP_PER_HOUR -> Decision.SKIP
        notified >= NOTIFY_CAP_PER_HOUR -> Decision.RECORD_ONLY
        else -> Decision.NOTIFY
    }
}
