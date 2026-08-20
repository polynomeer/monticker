package com.monticker.worker.detector

import com.monticker.worker.marketdata.GeneratedTick
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Instant

/**
 * EventDetector는 VolumeSurgeDetector/PriceSpikeDetector 두 하위 감지기를 조합한다.
 * 이 클래스 자체의 유일한 로직은 "둘 다 감지되면 어느 이벤트를 우선할지" 뿐이므로,
 * 그 로직만 검증하려면 감지기를 목으로 대체해 호출 여부를 세는 게 아니라
 * 진짜 PriceSpikeDetector/VolumeSurgeDetector를 실제 협력자로 사용하고
 * (둘 다 이 모듈 내부 클래스이지 시스템 경계가 아니다) Redis(진짜 경계)만 목으로 제어해서
 * "관측 가능한 결과"(반환값, StockEventWriter에 실제로 기록된 이벤트)로 검증한다.
 */
class EventDetectorTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val writer = mockk<StockEventWriter>(relaxed = true)
    private val ops = mockk<ValueOperations<String, String>>()

    private val priceSpikeDetector = PriceSpikeDetector(redisTemplate, writer)
    private val volumeSurgeDetector = VolumeSurgeDetector(redisTemplate, writer)
    private val eventDetector = EventDetector(volumeSurgeDetector, priceSpikeDetector)

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns ops
    }

    private fun makeTick(price: BigDecimal, volume: Long) = GeneratedTick(
        stockId = 1L, symbol = "005930", market = "KOSPI",
        price = price, volume = volume, tradeTime = Instant.now(),
    )

    @Test
    fun `detect는 가격과 거래량 감지기를 모두 실행하여 두 이벤트를 모두 기록한다`() {
        // 가격: prev=70000, ema=0.01(작음) → 71500이면 큰 ratio로 스파이크
        every { ops.get("detector:price:prev:005930") } returns "70000"
        every { ops.get("detector:price:ema:005930") } returns "0.01"
        // 거래량: ema=3000 → 15000이면 5배 서지
        every { ops.get("detector:volume:ema:005930") } returns "3000.0"
        every { ops.set(any(), any()) } just Runs

        eventDetector.detect(makeTick(price = BigDecimal("71500"), volume = 15_000))

        verify(exactly = 1) { writer.write(match { it.eventType == DetectedEventType.PRICE_SPIKE }) }
        verify(exactly = 1) { writer.write(match { it.eventType == DetectedEventType.VOLUME_SURGE }) }
    }

    @Test
    fun `detectWithType은 가격 급등과 거래량 급증이 동시에 감지되면 PRICE_SPIKE를 우선한다`() {
        every { ops.get("detector:price:prev:005930") } returns "70000"
        every { ops.get("detector:price:ema:005930") } returns "0.01"
        every { ops.get("detector:volume:ema:005930") } returns "3000.0"

        val result = eventDetector.detectWithType(makeTick(price = BigDecimal("71500"), volume = 15_000))

        assertThat(result).isEqualTo("PRICE_SPIKE")
    }

    @Test
    fun `detectWithType은 거래량 급증만 감지되면 VOLUME_SURGE를 반환한다`() {
        // 가격은 스파이크 기준(ratio 3x) 미만으로 유지
        every { ops.get("detector:price:prev:005930") } returns "70000"
        every { ops.get("detector:price:ema:005930") } returns "10.0" // 충분히 큰 ema → ratio < 3
        every { ops.get("detector:volume:ema:005930") } returns "3000.0" // 15000/3000 = 5x

        val result = eventDetector.detectWithType(makeTick(price = BigDecimal("70050"), volume = 15_000))

        assertThat(result).isEqualTo("VOLUME_SURGE")
    }

    @Test
    fun `detectWithType은 아무 것도 감지되지 않으면 null을 반환한다`() {
        // 가격/거래량 모두 첫 틱(EMA 미초기화)이라 감지 불가
        every { ops.get(any()) } returns null

        val result = eventDetector.detectWithType(makeTick(price = BigDecimal("70000"), volume = 1_000))

        assertThat(result).isNull()
    }
}
