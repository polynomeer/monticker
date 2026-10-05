package com.monticker.worker.marketindex

import com.monticker.worker.marketdata.MarketSchedule
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/**
 * ADR-071 — 개발용 지수·환율 공급자. 국내 장 시간(MarketSchedule KOSPI 기준)에만 값이 움직인다.
 * 저장되는 모든 값은 is_mocked=true다 — 화면은 이 값을 실시세처럼 보여주지 않는다.
 */
class MockMarketIndexProvider(
    private val random: Random = Random.Default,
    private val isKrSessionOpen: (Instant) -> Boolean = { MarketSchedule.getTickConfig("KOSPI", "KOSPI").status != MarketSchedule.MarketStatus.CLOSED },
) : MarketIndexProvider {

    override val source = "MOCK"
    override val isMock = true

    companion object {
        /** 처음 값(저장된 값이 없을 때). 실제 시세가 아니라 그럴듯한 출발점일 뿐이다. */
        val BASE: Map<MarketIndexCode, BigDecimal> = mapOf(
            MarketIndexCode.KOSPI to BigDecimal("2600.00"),
            MarketIndexCode.KOSDAQ to BigDecimal("850.00"),
            MarketIndexCode.USDKRW to BigDecimal("1380.00"),
        )
        /** 30초 한 번 움직일 때의 표준편차(비율) */
        private val STEP_VOL = mapOf(
            MarketIndexCode.KOSPI to 0.0004,
            MarketIndexCode.KOSDAQ to 0.0006,
            MarketIndexCode.USDKRW to 0.0002,
        )
        /** 하루 종가 변동 표준편차(비율) — 이력 백필용 */
        private val DAILY_VOL = mapOf(
            MarketIndexCode.KOSPI to 0.010,
            MarketIndexCode.KOSDAQ to 0.014,
            MarketIndexCode.USDKRW to 0.004,
        )
    }

    override fun fetch(previous: Map<MarketIndexCode, BigDecimal>, now: Instant): List<MarketIndexTick> {
        // 장이 닫혀 있고 이미 값이 있으면 움직이지 않는다(마지막 값이 그대로 종가로 남는다).
        val open = isKrSessionOpen(now)
        return MarketIndexCode.entries.mapNotNull { code ->
            val last = previous[code]
            when {
                last == null -> MarketIndexTick(code, BASE.getValue(code), now)
                !open -> null
                else -> MarketIndexTick(code, step(last, STEP_VOL.getValue(code)), now)
            }
        }
    }

    override fun history(code: MarketIndexCode, endExclusive: LocalDate, tradingDays: Int): List<Pair<LocalDate, BigDecimal>> {
        // 최근 날짜에서 거꾸로 걸어가며 만든 뒤 뒤집는다 — 마지막 날의 값이 BASE 근처가 되도록.
        val out = ArrayList<Pair<LocalDate, BigDecimal>>(tradingDays)
        var date = endExclusive.minusDays(1)
        var value = BASE.getValue(code)
        while (out.size < tradingDays) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                out.add(date to value)
                value = step(value, DAILY_VOL.getValue(code))
            }
            date = date.minusDays(1)
        }
        return out.reversed()
    }

    private fun step(value: BigDecimal, vol: Double): BigDecimal {
        // Box–Muller 정규분포 걸음. 값이 0 이하로 가지 않게 하한을 둔다.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
        val factor = (1.0 + z * vol).coerceIn(0.9, 1.1)
        return value.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP)
            .max(BigDecimal("0.01"))
    }
}
