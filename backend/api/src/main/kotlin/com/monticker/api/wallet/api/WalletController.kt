package com.monticker.api.wallet.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.wallet.application.BehaviorScoreService
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.LedgerService
import com.monticker.api.wallet.application.ReceiptService
import com.monticker.api.wallet.application.ReplayService
import com.monticker.api.wallet.application.WalletService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/wallet")
class WalletController(
    private val walletService: WalletService,
    private val ledgerService: LedgerService,
    private val replayService: ReplayService,
    private val behaviorScoreService: BehaviorScoreService,
    private val emotionTagService: EmotionTagService,
) {

    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun getWalletMap() = ResponseEntity.ok(walletService.getWalletMap(userId()))

    @GetMapping("/ledger")
    fun getLedger() = ResponseEntity.ok(ledgerService.getLedger(userId()))

    @GetMapping("/replay")
    fun getDailyReplay(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ) = ResponseEntity.ok(replayService.getDailyReplay(userId(), date))

    @GetMapping("/score")
    fun getScore() = ResponseEntity.ok(
        behaviorScoreService.getOrCalculateScore(userId(), LocalDate.now())
    )

    @GetMapping("/emotion-analysis")
    @RateLimited(limit = 10, windowSec = 3600, keyPrefix = "wallet.emotion")
    fun getEmotionAnalysis() = ResponseEntity.ok(emotionTagService.getAnalysis(userId()))
}

@RestController
@RequestMapping("/api/paper/trades")
class TradeReceiptController(
    private val receiptService: ReceiptService,
    private val emotionTagService: EmotionTagService,
) {

    private fun userId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/{id}/receipt")
    fun getReceipt(@PathVariable id: Long): ResponseEntity<*> {
        return try {
            ResponseEntity.ok(receiptService.getReceipt(userId(), id))
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build<Unit>()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @PostMapping("/{id}/emotion")
    fun saveEmotion(
        @PathVariable id: Long,
        @RequestBody req: EmotionRequest,
    ) = ResponseEntity.ok(emotionTagService.saveTag(userId(), id, req.emotion, req.memo))

    @GetMapping("/{id}/emotion")
    fun getEmotion(@PathVariable id: Long): ResponseEntity<*> {
        val tag = emotionTagService.getTag(id)
        return if (tag != null) ResponseEntity.ok(tag)
        else ResponseEntity.notFound().build<Unit>()
    }
}

data class EmotionRequest(val emotion: String, val memo: String? = null)
