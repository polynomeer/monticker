package com.monticker.api.matching.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.common.time.KstPeriod
import com.monticker.api.matching.application.ExecutionQualityService
import com.monticker.api.matching.application.MatchingService
import com.monticker.api.matching.application.SubmitOrderRequest
import com.monticker.api.matching.submit.OrderOrigin
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

@Validated
@RestController
@RequestMapping("/api/matching")
class MatchingController(
    private val matchingService: MatchingService,
    private val executionQualityService: ExecutionQualityService,
) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @PostMapping("/orders")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "matching.order")
    fun submitOrder(@RequestBody body: MatchingOrderRequest): ResponseEntity<*> {
        val req = body.toSubmitRequest()
        return ResponseEntity.ok(matchingService.submitOrderChecked(
            userId         = userId(),
            stockId        = req.stockId,
            side           = req.side,
            quantity       = req.quantity,
            estimatedPrice = req.limitPrice ?: java.math.BigDecimal.ZERO,
            req            = req,
        ))
    }

    @DeleteMapping("/orders/{id}")
    @RateLimited(limit = 30, windowSec = 60, keyPrefix = "matching.cancel")
    fun cancelOrder(@PathVariable id: Long): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.cancelOrder(userId(), id))

    @GetMapping("/orders")
    fun getActiveOrders(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getActiveOrders(userId()))

    @GetMapping("/orders/{id}/fills")
    fun getOrderFills(@PathVariable id: Long): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getOrderFills(userId(), id))

    @GetMapping("/fills")
    fun getMyFills(): ResponseEntity<*> =
        ResponseEntity.ok(matchingService.getMyFills(userId()))

    /**
     * ADR-091 — 내 모의 체결의 평균 슬리피지(접수 시점 최우선 호가 대비, bps)와 엔진 지연(시장가 접수 → 체결, ms).
     * 기간은 체결 시각 기준 KST 날짜 `[from, to]`, 생략하면 최근 30일, 최대 1년.
     */
    @GetMapping("/execution-quality")
    fun getExecutionQuality(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<*> =
        ResponseEntity.ok(executionQualityService.summary(userId(), KstPeriod.parse(from, to, defaultDays = 30)))
}

/**
 * 사용자 주문 요청 본문. 서버 내부 필드(멱등 키 ADR-051, 진입 출처 ADR-085)는 여기 없다 — 예전엔 [SubmitOrderRequest]를
 * 본문에 바로 바인딩해 클라이언트가 `idempotencyKey: "WR:…"`를 보내 Watch Rule 주문인 척할 수 있었다.
 * 알 수 없는 필드는 무시되므로 그런 값을 보내도 효과가 없다.
 */
data class MatchingOrderRequest(
    val stockId: Long,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
) {
    fun toSubmitRequest() = SubmitOrderRequest(
        stockId = stockId, side = side, orderType = orderType, quantity = quantity, limitPrice = limitPrice,
        idempotencyKey = null, origin = OrderOrigin.MANUAL,
    )
}
