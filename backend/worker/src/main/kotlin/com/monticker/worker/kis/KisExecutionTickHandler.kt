package com.monticker.worker.kis

import com.monticker.worker.kafka.TickKafkaProducer
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.MarketSchedule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * KIS H0STCNT0 (국내주식 실시간체결가) 파서 → market.ticks 발행.
 *
 * pipe-delimited 필드 순서는 KIS 공식 저장소에서 직접 확인했다
 * (koreainvestment/open-trading-api, examples_llm/domestic_stock/ccnl_krx/ccnl_krx.py) —
 * 46개 필드 중 이번에 쓰는 건 아래 4개뿐이다:
 *   idx 0  : MKSC_SHRN_ISCD (종목코드)
 *   idx 1  : STCK_CNTG_HOUR (체결시각, HHMMSS)
 *   idx 2  : STCK_PRPR      (현재가)
 *   idx 12 : CNTG_VOL       (이 체결의 거래량)
 *
 * 주의: 체결이 몰리면 한 메시지에 46필드 블록이 dataCount만큼 반복될 수 있는데,
 * 기존 H0STASP0 파서와 마찬가지로 이번에도 첫 블록만 처리한다(ADR-030) — 고빈도
 * 구간에서 일부 체결이 유실될 수 있다는 걸 인지한 상태의 의도적 단순화다.
 */
@Component
class KisExecutionTickHandler(
    private val coverage: KisCoverageProvider,
    private val producer: TickKafkaProducer,
) : KisRealtimeHandler {
    override val trId = "H0STCNT0"

    private val log = LoggerFactory.getLogger(javaClass)
    private val seoul = ZoneId.of("Asia/Seoul")

    override fun handle(parts: List<String>) {
        val fields = parts.drop(3)
        if (fields.size < 15) return

        val symbol = fields[0]
        val target = coverage.bySymbol[symbol] ?: return
        val price = fields[2].toBigDecimalOrNull()
        if (price == null) {
            log.warn("H0STCNT0 가격 파싱 실패: symbol={} raw={}", symbol, fields[2])
            return
        }
        val volume = fields[12].toLongOrNull() ?: 0L

        producer.publish(
            GeneratedTick(
                stockId = target.stockId,
                symbol = target.symbol,
                market = target.market,
                price = price,
                volume = volume,
                tradeTime = parseTradeTime(fields[1]),
                marketStatus = MarketSchedule.getTickConfig(target.symbol, target.market).status.name,
            )
        )
    }

    private fun parseTradeTime(hhmmss: String): Instant {
        if (hhmmss.length != 6) return Instant.now()
        return runCatching {
            val time = LocalTime.of(
                hhmmss.substring(0, 2).toInt(),
                hhmmss.substring(2, 4).toInt(),
                hhmmss.substring(4, 6).toInt(),
            )
            LocalDate.now(seoul).atTime(time).atZone(seoul).toInstant()
        }.getOrElse { Instant.now() }
    }
}
