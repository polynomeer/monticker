package com.monticker.api.wallet.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.time.KstPeriod
import com.monticker.api.wallet.application.BehaviorScoreResponse
import com.monticker.api.wallet.application.BehaviorScoreService
import com.monticker.api.wallet.application.DailyReturnService
import com.monticker.api.wallet.application.DailyReturnsResponse
import com.monticker.api.wallet.application.EmotionAnalysisResponse
import com.monticker.api.wallet.application.ScoreDetailService
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.LedgerService
import com.monticker.api.wallet.application.ReceiptService
import com.monticker.api.wallet.application.ReplayService
import com.monticker.api.wallet.application.WalletService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Validated
@RestController
@RequestMapping("/api/wallet")
class WalletController(
    private val walletService: WalletService,
    private val ledgerService: LedgerService,
    private val replayService: ReplayService,
    private val behaviorScoreService: BehaviorScoreService,
    private val emotionTagService: EmotionTagService,
    private val reconciliationQueryService: com.monticker.api.wallet.application.ReconciliationQueryService,
    private val scoreDetailService: ScoreDetailService,
    private val dailyReturnService: DailyReturnService,
) {

    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun getWalletMap() = ResponseEntity.ok(walletService.getWalletMap(userId()))

    /** ADR-043 — 커서 페이징. cursor 생략 = 첫 페이지, limit은 서버가 최대 50으로 clamp. */
    @GetMapping("/ledger")
    fun getLedger(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
    ) = ResponseEntity.ok(ledgerService.getLedger(userId(), cursor, limit))

    @GetMapping("/replay")
    fun getDailyReplay(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = ResponseEntity.ok(replayService.getDailyReplay(userId(), date))

    /** ADR-043 일일 대사 결과 — "잔액 불일치 N건". 스냅샷만 읽는다(실시간 재계산 없음). */
    @GetMapping("/reconciliation")
    fun getReconciliation(@RequestParam(defaultValue = "90") days: Int) =
        ResponseEntity.ok(reconciliationQueryService.summary(userId(), days))

    /**
     * 오늘(KST) 점수 + ADR-091 세부 지표. 이전엔 `LocalDate.now()`(서버 시간대)라 UTC 서버에서는 KST 00~09시에
     * 전날 점수를 보였다.
     */
    @GetMapping("/score")
    fun getScore(): ResponseEntity<BehaviorScoreResponse> {
        val userId = userId()
        val score = behaviorScoreService.getOrCalculateScore(userId, KstPeriod.today())
        return ResponseEntity.ok(score.copy(details = scoreDetailService.weekly(userId)))
    }

    /**
     * 감정별 분포·평균 수익률. ADR-091 — `from`·`to`(KST, 거래 체결일 기준, 양 끝 포함, 최대 1년)를 주면 그 구간만,
     * 둘 다 생략하면 전체 기간(기존 동작). 한 쿼리로 바뀌어(N+1 제거) 한도를 시간당 60회로 올렸다.
     */
    @GetMapping("/emotion-analysis")
    @RateLimited(limit = 60, windowSec = 3600, keyPrefix = "wallet.emotion")
    fun getEmotionAnalysis(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<EmotionAnalysisResponse> {
        val period = if (from == null && to == null) null else KstPeriod.parse(from, to, defaultDays = 7)
        return ResponseEntity.ok(emotionTagService.getAnalysis(userId(), period))
    }

    /** ADR-091 — 날짜별 수익률(그날 손익 ÷ 그날 시작 평가자산, KST). 생략하면 최근 7일, 최대 1년. */
    @GetMapping("/daily-returns")
    fun getDailyReturns(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<DailyReturnsResponse> =
        ResponseEntity.ok(dailyReturnService.daily(userId(), KstPeriod.parse(from, to, defaultDays = 7)))
}

@Validated
@RestController
@RequestMapping("/api/paper/trades")
class TradeReceiptController(
    private val receiptService: ReceiptService,
    private val emotionTagService: EmotionTagService,
) {

    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/{id}/receipt")
    fun getReceipt(@PathVariable id: Long): ResponseEntity<*> =
        ResponseEntity.ok(receiptService.getReceipt(userId(), id))

    @PostMapping("/{id}/emotion")
    fun saveEmotion(
        @PathVariable id: Long,
        @RequestBody req: EmotionRequest,
    ) = ResponseEntity.ok(emotionTagService.saveTag(userId(), id, req.emotion, req.memo))

    @GetMapping("/{id}/emotion")
    fun getEmotion(@PathVariable id: Long): ResponseEntity<*> {
        val tag = emotionTagService.getTag(userId(), id)
        return if (tag != null) ResponseEntity.ok(tag)
        else ResponseEntity.notFound().build<Unit>()
    }
}

data class EmotionRequest(val emotion: String, val memo: String? = null)
