package com.monticker.api.wallet.report

import com.monticker.api.common.time.KstPeriod
import com.monticker.api.wallet.application.EmotionAnalysisResponse
import com.monticker.api.wallet.application.Ratio
import com.monticker.api.wallet.application.ScoreDetails
import com.monticker.api.wallet.application.WeeklyRatio
import com.monticker.api.wallet.application.WeeklyScore
import org.springframework.web.util.HtmlUtils
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** ADR-101 — 리포트 대상 주. 실행 시각이 속한 KST 주의 **직전 주**(월 00:00 ~ 다음 월 00:00 KST). */
object ReportWeek {
    fun at(now: Instant): KstPeriod = KstPeriod.weekOf(KstPeriod.weekStart(KstPeriod.today(now)).minusDays(7))
}

/**
 * ADR-101 — 주간 리포트를 받을 사용자의 설정 판정. 리포트는 끌 수 있는 이메일 알림이라 전체 알림·이메일 채널·리포트 스위치가
 * **모두** 켜져 있어야 보낸다. 방해 금지 시간은 보지 않는다(이메일은 방해 금지 대상이 아니다 — ADR-093).
 * 판정 SQL(`WeeklyBehaviorReportJob`)과 옛 Redis 설정(행이 없는 사용자) 판정이 이 규칙을 같이 쓴다.
 */
data class ReportPreference(val allEnabled: Boolean = true, val emailEnabled: Boolean = true, val weeklyReportEmail: Boolean = true) {
    val wantsReport: Boolean get() = allEnabled && emailEnabled && weeklyReportEmail
}

/** 수신 자격(설정 외) — 탈퇴하지 않았고 이메일을 인증했다. 지난주 모의 체결이 1건 이상이어야 보낸다. */
data class ReportRecipient(val deleted: Boolean, val emailVerified: Boolean, val tradesLastWeek: Int, val preference: ReportPreference) {
    val eligible: Boolean get() = !deleted && emailVerified && tradesLastWeek > 0 && preference.wantsReport
}

data class EmotionShare(val emotion: String, val count: Int, val sharePct: Double)

/** 리포트 내용 — 모두 ADR-091 정의(점수 카드·리플레이와 같은 함수)로 계산한 값이다. 종목 정보는 담지 않는다. */
data class WeeklyBehaviorReport(
    val week: KstPeriod,
    val buyCount: Int,
    val sellCount: Int,
    /** 그 전 주 체결 수(전주 대비) */
    val previousTradeCount: Int,
    val planAdherence: WeeklyRatio,
    val stopLossAdherence: WeeklyRatio,
    val behaviorScore: WeeklyScore,
    /** 감정 태그가 남은 체결 수(분포의 분모) */
    val taggedCount: Int,
    val emotions: List<EmotionShare>,
) {
    val tradeCount: Int get() = buyCount + sellCount

    companion object {
        fun of(week: KstPeriod, buyCount: Int, sellCount: Int, previousTradeCount: Int, details: ScoreDetails, emotion: EmotionAnalysisResponse) =
            WeeklyBehaviorReport(
                week = week,
                buyCount = buyCount,
                sellCount = sellCount,
                previousTradeCount = previousTradeCount,
                planAdherence = details.planAdherence,
                stopLossAdherence = details.stopLossAdherence,
                behaviorScore = details.behaviorScore,
                taggedCount = emotion.totalCount,
                emotions = emotion.stats.map { EmotionShare(it.emotion, it.count, it.sharePct ?: 0.0) },
            )
    }
}

data class RenderedEmail(val subject: String, val text: String, val html: String)

/**
 * ADR-101 — 주간 리포트 이메일 본문. **행동 피드백만** 쓴다: 종목명·가격·수익률·"사라/팔라"는 넣지 않는다.
 * 사용자 입력(닉네임)은 HTML 이스케이프한다. 숫자가 없으면(분모 0) "—"로 쓴다(지어낸 숫자를 보이지 않는다, ADR-091).
 */
object WeeklyBehaviorReportRenderer {
    const val DISCLAIMER = "monticker는 투자자문·투자중개업자가 아니며, 제공 정보는 투자 권유가 아닙니다. " +
        "이 리포트는 모의투자 기록으로 계산한 행동 지표이며 수익을 보장하지 않습니다."

    /** 백엔드 EmotionType ↔ 화면 문구(apps/web components/wallet/emotions.ts와 같다) */
    val EMOTION_LABELS: Map<String, String> = mapOf(
        "CONFIDENT" to "확신", "PLANNED" to "계획대로", "LONG_TERM" to "장기 투자", "NEWS_BASED" to "뉴스 보고",
        "REBALANCING" to "비중 조절", "ANXIOUS" to "불안", "FOMO" to "FOMO", "IMPATIENT" to "조급함",
        "FOLLOWING" to "따라삼", "AVERAGING_DOWN" to "물타기", "INTUITION" to "직감", "OTHER" to "기타",
    )

    private val DAY = DateTimeFormatter.ofPattern("M/d", Locale.KOREAN)

    fun render(report: WeeklyBehaviorReport, nickname: String, baseUrl: String): RenderedEmail {
        val range = "${report.week.from.format(DAY)}(월) ~ ${report.week.to.format(DAY)}(일)"
        val settingsUrl = settingsUrl(baseUrl)
        val walletUrl = "${baseUrl.trimEnd('/')}/wallet"
        val rows = listOf(
            Line("모의 체결", "${report.tradeCount}건 (매수 ${report.buyCount} · 매도 ${report.sellCount})", tradeDelta(report)),
            Line("계획 준수율", ratio(report.planAdherence.thisWeek), pp(report.planAdherence.deltaPp)),
            Line("손절 준수율", ratio(report.stopLossAdherence.thisWeek), pp(report.stopLossAdherence.deltaPp)),
            Line("행동 점수(주간 평균)", score(report.behaviorScore.thisWeekAvg), scoreDelta(report.behaviorScore)),
        )
        val emotionLines = report.emotions.map { "${label(it.emotion)} ${it.count}건 (${it.sharePct.roundToInt()}%)" }
        val subject = "[monticker] 주간 투자 행동 리포트 · $range"

        val text = buildString {
            appendLine("${nickname}님, 지난주($range KST) 모의투자 행동을 정리했습니다.")
            appendLine()
            rows.forEach { appendLine("- ${it.label}: ${it.value}${it.delta?.let { d -> " (전주 대비 $d)" } ?: ""}") }
            appendLine()
            appendLine("감정 분포 (태그 ${report.taggedCount}건)")
            if (emotionLines.isEmpty()) appendLine("- 감정 태그를 남긴 체결이 없습니다") else emotionLines.forEach { appendLine("- $it") }
            appendLine()
            appendLine(NOTES.joinToString("\n") { "* $it" })
            appendLine()
            appendLine("지갑에서 자세히 보기: $walletUrl")
            appendLine("이 메일을 그만 받으려면 알림 설정에서 '주간 투자 행동 리포트'를 끄세요: $settingsUrl")
            appendLine()
            append(DISCLAIMER)
        }

        val e = ::esc
        val html = buildString {
            append("<div style=\"font-family:-apple-system,'Pretendard',sans-serif;max-width:560px;color:#1f2330;\">")
            append("<h2 style=\"margin:0 0 4px;\">주간 투자 행동 리포트</h2>")
            append("<p style=\"margin:0 0 16px;color:#666;\">${e(range)} · KST</p>")
            append("<p>${e(nickname)}님, 지난주 모의투자 행동을 정리했습니다.</p>")
            append("<table style=\"border-collapse:collapse;width:100%;\">")
            rows.forEach {
                append("<tr><td style=\"padding:6px 0;color:#666;\">${e(it.label)}</td>")
                append("<td style=\"padding:6px 0;text-align:right;\"><b>${e(it.value)}</b>")
                it.delta?.let { d -> append("<br><span style=\"color:#888;font-size:12px;\">전주 대비 ${e(d)}</span>") }
                append("</td></tr>")
            }
            append("</table>")
            append("<h3 style=\"margin:20px 0 6px;font-size:15px;\">감정 분포 <span style=\"color:#888;font-weight:normal;\">(태그 ${report.taggedCount}건)</span></h3>")
            if (emotionLines.isEmpty()) append("<p style=\"color:#666;\">감정 태그를 남긴 체결이 없습니다.</p>")
            else append("<ul style=\"margin:0;padding-left:18px;\">").also { emotionLines.forEach { l -> append("<li>${e(l)}</li>") } }.append("</ul>")
            append("<p style=\"color:#888;font-size:12px;margin-top:16px;\">")
            append(NOTES.joinToString("<br>") { e(it) })
            append("</p>")
            append("<p><a href=\"${e(walletUrl)}\">지갑에서 자세히 보기</a></p>")
            append("<p style=\"color:#888;font-size:12px;\">이 메일을 그만 받으려면 <a href=\"${e(settingsUrl)}\">알림 설정</a>에서 ")
            append("‘주간 투자 행동 리포트’를 끄세요.</p>")
            append("<p style=\"color:#888;font-size:11px;\">${e(DISCLAIMER)}</p>")
            append("</div>")
        }
        return RenderedEmail(subject, text, html)
    }

    fun settingsUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/settings/notifications"

    private val NOTES = listOf(
        "계획 준수율: 판정 가능한 체결 중 미리 정한 규칙(조건부 주문·Watch Rule·전략)이 낸 주문과 ‘계획대로’ 태그의 비율",
        "손절 준수율: 손절을 정해 둔 포지션의 손실 매도 중 처음 정한 손절선을 지킨 비율",
        "값이 없으면(—) 판정할 거래가 없었다는 뜻입니다",
    )

    private data class Line(val label: String, val value: String, val delta: String?)

    private fun esc(s: String): String = HtmlUtils.htmlEscape(s, "UTF-8")

    private fun label(code: String) = EMOTION_LABELS[code] ?: "기타"

    private fun ratio(r: Ratio): String = r.pct?.let { "${it.roundToInt()}% (${r.numerator}/${r.denominator})" } ?: "—"

    private fun pp(d: Double?): String? = d?.let { signed(it, "%p") }

    private fun score(v: Double?): String = v?.let { "%.1f점".format(it) } ?: "—"

    private fun scoreDelta(s: WeeklyScore): String? = s.delta?.let { signed(it, "점") }

    private fun tradeDelta(r: WeeklyBehaviorReport): String = (r.tradeCount - r.previousTradeCount).let { d ->
        when {
            d > 0 -> "+${d}건"
            d < 0 -> "−${-d}건"
            else -> "변화 없음"
        }
    }

    private fun signed(v: Double, unit: String): String {
        val rounded = (v * 10).roundToInt() / 10.0
        return when {
            rounded > 0 -> "+%.1f%s".format(rounded, unit)
            rounded < 0 -> "−%.1f%s".format(abs(rounded), unit)
            else -> "변화 없음"
        }
    }
}
