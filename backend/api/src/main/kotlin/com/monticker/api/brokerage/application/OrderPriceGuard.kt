package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.KrxPriceRules
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ADR-081 — 실거래 지정가의 KRX 호가 단위·가격제한폭을 **증권사 호출 전에** 확인한다. 위반이면 [IllegalArgumentException](400) —
 * 의도 행(PENDING_SUBMIT)도 남지 않는다(주문 준비 tx1 안에서 의도 기록보다 먼저 돈다).
 *
 * - 호가 단위는 순수 계산이라 언제나 건다.
 * - 가격제한폭은 기준가(전 거래일 종가)를 **믿을 수 있을 때만** 건다. 믿을 수 없으면 건너뛰고 증권사 판정에 맡긴다 — 증권사도
 *   같은 규칙으로 거부하므로 건너뛰어도 돈이 잘못 나가지 않는다. 반대로 틀린 기준가로 막으면 정상 주문이 막힌다(ADR-055와 같은
 *   "출처가 확인되지 않은 데이터로 실주문을 좌우하지 않는다").
 *   1. 실제 돈을 움직이는 계좌는 그 종목이 실시세 커버리지(ADR-060)에 있어야 한다 — 아니면 일봉이 합성 시세로 만들어졌다.
 *   2. 직전 거래일 일봉이 [BASE_LOOKBACK_DAYS] 안에 있어야 한다.
 *   3. 오늘(KST) 시세가 있어야 하고, 그 시세가 계산한 제한폭 **안에** 있어야 한다. 권리락·액면분할처럼 기준가가 전일 종가와
 *      다른 날은 현재가가 계산한 폭 밖으로 나가므로 여기서 걸러진다(자기 일관성 검사).
 */
@Component
class OrderPriceGuard(
    private val jdbc: JdbcTemplate,
    private val priceFeedMonitor: PriceFeedMonitor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [movesRealMoney]는 제한폭을 판정할 때만 묻는다(시장가·해외 종목에는 필요 없다). */
    fun check(stockId: Long, request: BrokerageOrderRequest, movesRealMoney: () -> Boolean, now: Instant = Instant.now()) {
        if (request.orderType != "LIMIT") return
        val price = request.limitPrice ?: throw IllegalArgumentException("지정가 주문에는 가격이 필요합니다.")
        require(price.signum() > 0) { "지정가는 0원보다 커야 합니다." }

        val market = jdbc.queryForList("SELECT market FROM stocks WHERE id = ?", String::class.java, stockId).firstOrNull()
        if (!KrxPriceRules.isKrxMarket(market)) return

        if (!KrxPriceRules.isOnTick(price)) {
            count("tick_rejected")
            val tick = KrxPriceRules.tickSize(price)
            val below = price.divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
            throw IllegalArgumentException(
                "호가 단위에 맞지 않는 가격입니다: ${price.stripTrailingZeros().toPlainString()}원. 이 가격대의 호가 단위는 ${won(tick)}원입니다(예: ${won(below)}원 또는 ${won(below + tick)}원).",
            )
        }
        checkBand(stockId, price, movesRealMoney, now)
    }

    private fun checkBand(stockId: Long, price: BigDecimal, movesRealMoney: () -> Boolean, now: Instant) {
        if (movesRealMoney() && !priceFeedMonitor.isCovered(stockId)) return skip("unverified_source")

        val todayStart = LocalDate.ofInstant(now, KST).atStartOfDay(KST).toInstant()
        val base = jdbc.queryForList(
            """SELECT close FROM candles_1d WHERE stock_id = ? AND candle_time < ? AND candle_time >= ?
               ORDER BY candle_time DESC LIMIT 1""",
            BigDecimal::class.java, stockId, Timestamp.from(todayStart), Timestamp.from(todayStart.minusSeconds(BASE_LOOKBACK_DAYS * 86_400)),
        ).firstOrNull()?.takeIf { it.signum() > 0 } ?: return skip("no_base")

        val current = jdbc.queryForList(
            "SELECT close FROM candles_1m WHERE stock_id = ? AND candle_time >= ? ORDER BY candle_time DESC LIMIT 1",
            BigDecimal::class.java, stockId, Timestamp.from(todayStart),
        ).firstOrNull() ?: return skip("no_current")

        val band = KrxPriceRules.band(base)
        if (current !in band) {
            // 기준가가 전일 종가와 다른 날(권리락·분할·병합)이거나 일봉이 틀렸다. 이 기준가로는 막지 않는다.
            log.warn("가격제한폭 검사 건너뜀 — 현재가가 계산한 폭 밖: stockId={} base={} current={}", stockId, base, current)
            return skip("inconsistent_base")
        }
        if (price !in band) {
            count("band_rejected")
            throw IllegalArgumentException(
                "가격제한폭을 벗어난 가격입니다: ${won(price)}원. 전일 종가 ${won(base)}원 기준 ±30%" +
                    "(${won(band.start.setScale(0, RoundingMode.CEILING))}원 ~ ${won(band.endInclusive.setScale(0, RoundingMode.FLOOR))}원) 안에서 주문해주세요.",
            )
        }
        count("band_passed")
    }

    private fun skip(reason: String) {
        meterRegistry.counter("brokerage_order_price_check_total", "result", "band_skipped", "reason", reason).increment()
    }

    private fun count(result: String) {
        meterRegistry.counter("brokerage_order_price_check_total", "result", result, "reason", "").increment()
    }

    private fun won(v: BigDecimal) = "%,d".format(v.setScale(0, RoundingMode.DOWN).toLong())

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        /** 연휴(설·추석 최장 5~6일)를 넘는 공백이면 직전 거래일 일봉이 아니라고 본다. */
        const val BASE_LOOKBACK_DAYS = 10L
    }
}
