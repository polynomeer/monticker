package com.monticker.api.quant.application

import com.monticker.api.quant.domain.ForwardTestStatus
import com.monticker.api.quant.domain.QuantForwardTest
import com.monticker.api.quant.domain.QuantForwardTestEquityPoint
import com.monticker.api.quant.domain.QuantSignal
import com.monticker.api.quant.domain.SignalDirection
import com.monticker.api.quant.infrastructure.QuantForwardTestEquityRepository
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import com.monticker.api.quant.infrastructure.QuantSignalRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Quant Lab 포워드 테스트. ADR-024 참고 — 백테스트와 동일한 "하루 1번, 일봉 기준" 평가
 * 단위를 쓰되, 실행 상태(포지션/현금)가 하루하루 이어진다는 점이 다르다.
 */
@Service
class ForwardTestService(
    private val ruleSetRepository: RuleSetRepository,
    private val ruleSetService: RuleSetService,
    private val forwardTestRepository: QuantForwardTestRepository,
    private val signalRepository: QuantSignalRepository,
    private val equityRepository: QuantForwardTestEquityRepository,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun start(ruleSetId: String, userId: Long, req: StartForwardTestRequest): ForwardTestResponse {
        val doc = ruleSetRepository.findByIdAndUserId(ruleSetId, userId)
            .orElseThrow { NoSuchElementException("RuleSet $ruleSetId not found") }

        doc.publish() // require BACKTESTED, status -> RUNNING
        ruleSetRepository.save(doc)

        val ft = forwardTestRepository.save(
            QuantForwardTest(
                ruleSetId      = ruleSetId,
                ruleSetVersion = doc.version,
                stockId        = req.stockId,
                initialCapital = BigDecimal.valueOf(req.initialCapital),
                cash           = BigDecimal.valueOf(req.initialCapital),
            )
        )
        log.info("포워드 테스트 시작: ruleSetId={} stockId={} forwardTestId={}", ruleSetId, req.stockId, ft.id)
        return ft.toResponse(emptyList(), emptyList())
    }

    @Transactional
    fun stop(ruleSetId: String, userId: Long): ForwardTestResponse {
        val doc = ruleSetRepository.findByIdAndUserId(ruleSetId, userId)
            .orElseThrow { NoSuchElementException("RuleSet $ruleSetId not found") }
        val ft = forwardTestRepository.findByRuleSetIdAndStatus(ruleSetId, ForwardTestStatus.RUNNING)
            .orElseThrow { NoSuchElementException("실행 중인 포워드 테스트가 없습니다") }

        ft.stop()
        forwardTestRepository.save(ft)
        doc.unpublish()
        ruleSetRepository.save(doc)

        log.info("포워드 테스트 중지: ruleSetId={} forwardTestId={}", ruleSetId, ft.id)
        return ft.toResponse(
            equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(ft.id),
            signalRepository.findAllByForwardTestIdOrderBySignalTimeDesc(ft.id),
        )
    }

    fun getStatus(ruleSetId: String, userId: Long): ForwardTestResponse? {
        ruleSetRepository.findByIdAndUserId(ruleSetId, userId)
            .orElseThrow { NoSuchElementException("RuleSet $ruleSetId not found") }
        val ft = forwardTestRepository.findAllByRuleSetIdOrderByStartedAtDesc(ruleSetId).firstOrNull() ?: return null
        return ft.toResponse(
            equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(ft.id),
            signalRepository.findAllByForwardTestIdOrderBySignalTimeDesc(ft.id),
        )
    }

    /**
     * 하루치 평가. [ForwardTestScheduler]가 장 마감 후(KST 16:00) 하루 1번 호출한다.
     * lastEvaluatedDate로 같은 날 중복 호출을 막고, quant_signals의 (forward_test_id, eval_date)
     * 유니크 인덱스가 DB 레벨에서 한 번 더 막아준다(ADR-024).
     */
    @Transactional
    fun evaluateOne(ft: QuantForwardTest, asOfDate: LocalDate) {
        if (ft.lastEvaluatedDate == asOfDate) return

        val doc = ruleSetRepository.findById(ft.ruleSetId).orElse(null)
        if (doc == null) {
            log.warn("포워드 테스트 대상 룰셋을 찾을 수 없음: forwardTestId={} ruleSetId={}", ft.id, ft.ruleSetId)
            return
        }

        val ruleDef = ruleSetService.parseRuleDefinition(doc.ruleDefinition)
        val candles = ruleSetService.loadDailyCandles(ft.stockId, asOfDate.minusDays(400), asOfDate)
        if (candles.isEmpty() || candles.last().date != asOfDate) {
            log.info("오늘자 캔들이 아직 없어 평가를 건너뜀: forwardTestId={} date={}", ft.id, asOfDate)
            return
        }

        val idx = candles.lastIndex
        val price = candles.last().close.toDouble()
        var signal: SignalDirection? = null

        if (ft.isHolding) {
            val entryPrice = ft.holdingEntryPrice!!.toDouble()
            if (RuleEvaluator.evaluateExit(ruleDef.exitRules, candles, idx, entryPrice, price)) {
                val exitPrice = price * (1 - QuantBacktestEngine.SLIPPAGE_RATE)
                ft.closePosition(BigDecimal.valueOf(exitPrice))
                signal = SignalDirection.SELL
            }
        } else {
            if (RuleEvaluator.evaluateEntry(ruleDef.entryRules, candles, idx) && ft.cash.toDouble() > price) {
                val ratio      = ruleDef.positionSizing.value / 100.0
                val buyPrice   = price * (1 + QuantBacktestEngine.SLIPPAGE_RATE)
                val budget     = ft.cash.toDouble() * ratio
                val qty        = (budget / buyPrice).toInt().coerceAtLeast(1)
                val commission = qty * buyPrice * QuantBacktestEngine.COMMISSION_RATE
                val cost       = qty * buyPrice + commission
                if (cost <= ft.cash.toDouble()) {
                    ft.openPosition(qty, BigDecimal.valueOf(buyPrice), asOfDate)
                    signal = SignalDirection.BUY
                }
            }
        }

        ft.lastEvaluatedDate = asOfDate
        forwardTestRepository.save(ft)

        val history = equityRepository.findAllByForwardTestIdOrderByEvalDateAsc(ft.id)
        val totalEquity = ft.cash.toDouble() + ft.holdingQty * price
        val peak = maxOf(history.maxOfOrNull { it.equity.toDouble() } ?: ft.initialCapital.toDouble(), totalEquity)
        val drawdown = if (peak > 0) (peak - totalEquity) / peak * 100 else 0.0
        equityRepository.save(
            QuantForwardTestEquityPoint(
                forwardTestId = ft.id,
                evalDate      = asOfDate,
                equity        = BigDecimal.valueOf(totalEquity),
                drawdown      = BigDecimal.valueOf(drawdown),
            )
        )

        if (signal != null) {
            signalRepository.save(
                QuantSignal(
                    forwardTestId = ft.id,
                    ruleSetId     = ft.ruleSetId,
                    stockId       = ft.stockId,
                    direction     = signal,
                    signalTime    = Instant.now(),
                    evalDate      = asOfDate,
                )
            )
            log.info("포워드 테스트 신호 발생: forwardTestId={} direction={} price={}", ft.id, signal, price)
            messagingTemplate.convertAndSend(
                "/topic/rulesets/${ft.ruleSetId}/signals",
                mapOf(
                    "type" to "SIGNAL",
                    "direction" to signal.name,
                    "stockId" to ft.stockId,
                    "price" to price,
                    "evalDate" to asOfDate.toString(),
                ),
            )
        }
    }

    private fun QuantForwardTest.toResponse(
        equity: List<QuantForwardTestEquityPoint>,
        signals: List<QuantSignal>,
    ) = ForwardTestResponse(
        id                = id,
        ruleSetId         = ruleSetId,
        stockId           = stockId,
        status            = status.name,
        initialCapital    = initialCapital.toDouble(),
        cash              = cash.toDouble(),
        holdingQty        = holdingQty,
        holdingEntryPrice = holdingEntryPrice?.toDouble(),
        currentEquity     = equity.lastOrNull()?.equity?.toDouble() ?: initialCapital.toDouble(),
        startedAt         = startedAt.toString(),
        stoppedAt         = stoppedAt?.toString(),
        equityCurve       = equity.map { ForwardTestEquityPointResponse(it.evalDate.toString(), it.equity.toDouble(), it.drawdown.toDouble()) },
        signals           = signals.map { ForwardTestSignalResponse(it.direction.name, it.signalTime.toString(), it.evalDate?.toString()) },
    )
}

data class StartForwardTestRequest(
    val stockId: Long,
    val initialCapital: Double = 10_000_000.0,
)

data class ForwardTestEquityPointResponse(val date: String, val equity: Double, val drawdown: Double)
data class ForwardTestSignalResponse(val direction: String, val signalTime: String, val evalDate: String?)

data class ForwardTestResponse(
    val id: Long,
    val ruleSetId: String,
    val stockId: Long,
    val status: String,
    val initialCapital: Double,
    val cash: Double,
    val holdingQty: Int,
    val holdingEntryPrice: Double?,
    val currentEquity: Double,
    val startedAt: String,
    val stoppedAt: String?,
    val equityCurve: List<ForwardTestEquityPointResponse>,
    val signals: List<ForwardTestSignalResponse>,
)
