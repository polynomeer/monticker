package com.monticker.api.quant.application

import com.monticker.api.quant.domain.ForwardTestStatus
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.domain.QuantForwardTest
import com.monticker.api.quant.domain.QuantForwardTestEquityPoint
import com.monticker.api.quant.domain.QuantSignal
import com.monticker.api.quant.domain.SignalDirection
import com.monticker.api.quant.infrastructure.QuantForwardTestEquityRepository
import com.monticker.api.quant.infrastructure.QuantForwardTestRepository
import com.monticker.api.quant.infrastructure.QuantSignalRepository
import com.monticker.api.quant.infrastructure.RuleSetRepository
import org.slf4j.LoggerFactory
import com.monticker.api.common.notification.NotificationCategory
import com.monticker.api.common.notification.UserNotificationCommand
import org.springframework.context.ApplicationEventPublisher
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
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 평가일마다 지표 계산에 쓰는 일봉 창(달력일). 재실행(ForwardReplay)도 같은 값을 쓴다. */
        const val LOOKBACK_DAYS = 400L
    }

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
        // 중지 시점의 최종 일치율 — 계산 실패가 중지 자체를 막아서는 안 된다.
        runCatching {
            if (ruleSetService.verifyFingerprint(doc)) refreshMatch(ft, ruleSetService.parseRuleDefinition(doc.ruleDefinition))
        }.onFailure { log.warn("중지 시 포워드 일치율 계산 실패: forwardTestId={} error={}", ft.id, it.message) }
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

        if (!ruleSetService.verifyFingerprint(doc)) {
            log.error("룰셋 무결성 검증 실패: forwardTestId={} ruleSetId={} — 평가를 건너뜁니다", ft.id, ft.ruleSetId)
            return
        }

        val ruleDef = ruleSetService.parseRuleDefinition(doc.ruleDefinition)
        val candles = ruleSetService.loadDailyCandles(ft.stockId, asOfDate.minusDays(LOOKBACK_DAYS), asOfDate)
        if (candles.isEmpty() || candles.last().date != asOfDate) {
            log.info("오늘자 캔들이 아직 없어 평가를 건너뜀: forwardTestId={} date={}", ft.id, asOfDate)
            return
        }

        val idx = candles.lastIndex
        val price = candles.last().close.toDouble()
        var signal: SignalDirection? = null

        // ADR-078 — 판단은 백테스트와 같은 QuantDayStep으로 한다.
        val position = if (ft.isHolding) {
            SimPosition(ft.holdingQty, ft.holdingEntryPrice!!.toDouble(), ft.holdingEntryDate ?: asOfDate)
        } else null
        val aux = ruleSetService.loadAuxData(ft.stockId, candles.first().date, asOfDate, ruleDef)
        when (val action = QuantDayStep.decide(ruleDef, candles, idx, ft.cash.toDouble(), position, aux)) {
            is DayAction.Exit -> {
                ft.closePosition(BigDecimal.valueOf(action.fillPrice), BigDecimal.valueOf(action.commission))
                signal = SignalDirection.SELL
            }
            is DayAction.Enter -> {
                ft.openPosition(action.qty, BigDecimal.valueOf(action.fillPrice), asOfDate, BigDecimal.valueOf(action.commission))
                signal = SignalDirection.BUY
            }
            DayAction.Hold -> {}
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
                    price         = BigDecimal.valueOf(price),
                )
            )
            log.info("포워드 테스트 신호 발생: forwardTestId={} direction={} price={}", ft.id, signal, price)
            // ADR-082 — 룰셋 주인에게 알린다(알림 설정 "퀀트 시그널"). 이 트랜잭션이 커밋돼야 나간다. 하루·방향당 한 번.
            events.publishEvent(
                UserNotificationCommand(
                    userId = doc.userId,
                    category = NotificationCategory.QUANT_SIGNAL,
                    title = "${doc.name} ${if (signal == SignalDirection.BUY) "매수" else "매도"} 신호",
                    body = "포워드 테스트에서 ${if (signal == SignalDirection.BUY) "매수" else "매도"} 신호가 났습니다(종가 ${"%,.0f".format(price)}원, $asOfDate). " +
                        "모의 신호이며 실제 주문은 나가지 않았습니다.",
                    dedupKey = "quant-signal:${ft.id}:$asOfDate:${signal.name}",
                    data = mapOf("type" to "QUANT_SIGNAL", "ruleSetId" to ft.ruleSetId, "stockId" to ft.stockId, "direction" to signal.name),
                ),
            )
            messagingTemplate.convertAndSend(
                "/topic/rulesets/${ft.ruleSetId}/signals",
                mapOf(
                    "type" to "SIGNAL",
                    // 한 연결에서 여러 전략 토픽을 받는 클라이언트가 출처를 알 수 있게(웹 useRuleSetSignalsWs)
                    "rulesetId" to ft.ruleSetId,
                    "direction" to signal.name,
                    "stockId" to ft.stockId,
                    "price" to price,
                    "evalDate" to asOfDate.toString(),
                ),
            )
        }

        refreshMatch(ft, ruleDef)
    }

    /**
     * ADR-078 — 포워드 일치율 갱신. 같은 기간을 지금 저장된 데이터로 포워드와 같은 절차로 재실행해
     * 실제 포워드 신호와 비교한다. 지표 계산이 실패해도 평가 자체(포지션·신호)는 이미 끝났으므로
     * 여기서 난 예외는 삼키고 이전 값을 유지한다.
     */
    internal fun refreshMatch(ft: QuantForwardTest, ruleDef: com.monticker.api.quant.domain.RuleDefinition) {
        val to = ft.lastEvaluatedDate ?: return
        try {
            val from = ForwardReplay.firstEvaluationDate(ft.startedAt)
            if (to < from) return
            val candles = ruleSetService.loadDailyCandles(ft.stockId, from.minusDays(LOOKBACK_DAYS), to)
            val aux = ruleSetService.loadAuxData(ft.stockId, from.minusDays(LOOKBACK_DAYS), to, ruleDef)
            val replayed = ForwardReplay.replay(candles, ruleDef, ft.initialCapital.toDouble(), from, to, aux = aux)
            val actual = signalRepository.findAllByForwardTestIdOrderBySignalTimeDesc(ft.id)
                .mapNotNull { s -> s.evalDate?.let { ReplaySignal(it, s.direction) } }
            val match = ForwardReplay.compare(actual, replayed)
            ft.recordMatch(match.rate, match.matched, match.compared)
            forwardTestRepository.save(ft)
        } catch (e: Exception) {
            log.warn("포워드 일치율 계산 실패: forwardTestId={} error={}", ft.id, e.message)
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
        matchRate         = matchRate?.toDouble(),
        matchedSignals    = matchedSignals,
        comparedSignals   = comparedSignals,
        matchEvaluatedAt  = matchEvaluatedAt?.toString(),
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
    /** ADR-078 — 0~1. 비교할 신호가 아직 없거나 계산 전이면 null */
    val matchRate: Double?,
    val matchedSignals: Int?,
    val comparedSignals: Int?,
    val matchEvaluatedAt: String?,
    val equityCurve: List<ForwardTestEquityPointResponse>,
    val signals: List<ForwardTestSignalResponse>,
)
