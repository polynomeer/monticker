package com.monticker.api.brokerage.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.domain.*
import com.monticker.api.brokerage.infrastructure.BrokerageBalance
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ── 요청 DTO ───────────────────────────────────────────────────────────────────

data class ConnectRequest(
    val appKey: String,
    val appSecret: String,
    val accountNumber: String,
)

data class OrderRequest(
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
)

// ── 응답 DTO ───────────────────────────────────────────────────────────────────

data class AccountResponse(
    val id: Long,
    val provider: String,
    val accountNumber: String,
    val accountType: String,
    val isActive: Boolean,
    val connectedAt: Instant,
    val tokenValid: Boolean,
)

data class BalanceResponse(
    val cash: BigDecimal,
    val totalEvaluated: BigDecimal,
    val holdings: List<HoldingItem>,
) {
    data class HoldingItem(val symbol: String, val quantity: Int, val avgPrice: BigDecimal, val currentPrice: BigDecimal)
}

data class OrderResponse(
    val id: Long,
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val pgOrderId: String?,
    val status: String,
    val rejectReason: String?,
    val submittedAt: Instant,
    val filledAt: Instant?,
)

data class SettlementResponse(
    val id: Long,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val grossAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val netAmount: BigDecimal,
    val settleDate: LocalDate,
    val status: String,
    val settledAt: Instant?,
)

// ── 컨트롤러 ───────────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/brokerage")
class BrokerageController(
    private val brokerageService: BrokerageService,
    private val jwtTokenProvider: JwtTokenProvider,
) {
    private fun userId(token: String) =
        jwtTokenProvider.getUserId(token.removePrefix("Bearer "))

    // 계좌 연동
    @PostMapping("/connect")
    fun connect(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: ConnectRequest,
    ): ResponseEntity<AccountResponse> {
        val account = brokerageService.connect(userId(token), req.appKey, req.appSecret, req.accountNumber)
        return ResponseEntity.ok(account.toResponse())
    }

    // 연동 계좌 조회
    @GetMapping("/account")
    fun getAccount(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<AccountResponse> {
        val account = brokerageService.getAccount(userId(token))
        return ResponseEntity.ok(account.toResponse())
    }

    // 잔고 조회
    @GetMapping("/account/balance")
    fun getBalance(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<BalanceResponse> {
        val balance = brokerageService.getBalance(userId(token))
        return ResponseEntity.ok(
            BalanceResponse(
                cash           = balance.cash,
                totalEvaluated = balance.totalEvaluated,
                holdings       = balance.holdings.map {
                    BalanceResponse.HoldingItem(it.symbol, it.quantity, it.avgPrice, it.currentPrice)
                },
            )
        )
    }

    // 주문 제출
    @PostMapping("/orders")
    fun submitOrder(
        @RequestHeader("Authorization") token: String,
        @RequestBody req: OrderRequest,
    ): ResponseEntity<OrderResponse> {
        val order = brokerageService.submitOrder(
            userId(token),
            BrokerageOrderRequest(
                symbol     = req.symbol,
                side       = req.side.uppercase(),
                orderType  = req.orderType.uppercase(),
                quantity   = req.quantity,
                limitPrice = req.limitPrice,
            )
        )
        return ResponseEntity.ok(order.toResponse())
    }

    // 주문 상태 동기화
    @GetMapping("/orders/{id}/sync")
    fun syncOrder(
        @RequestHeader("Authorization") token: String,
        @PathVariable id: Long,
    ): ResponseEntity<OrderResponse> {
        val order = brokerageService.syncOrderStatus(userId(token), id)
        return ResponseEntity.ok(order.toResponse())
    }

    // 주문 취소
    @DeleteMapping("/orders/{id}")
    fun cancelOrder(
        @RequestHeader("Authorization") token: String,
        @PathVariable id: Long,
    ): ResponseEntity<OrderResponse> {
        val order = brokerageService.cancelOrder(userId(token), id)
        return ResponseEntity.ok(order.toResponse())
    }

    // 주문 내역 조회
    @GetMapping("/orders")
    fun getOrders(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<OrderResponse>> {
        val page = brokerageService.getOrders(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    // 정산 내역 조회
    @GetMapping("/settlements")
    fun getSettlements(
        @RequestHeader("Authorization") token: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<SettlementResponse>> {
        val page = brokerageService.getSettlements(userId(token), pageable).map { it.toResponse() }
        return ResponseEntity.ok(page)
    }

    // 정산 대기 내역
    @GetMapping("/settlements/pending")
    fun getPendingSettlements(
        @RequestHeader("Authorization") token: String,
    ): ResponseEntity<List<SettlementResponse>> {
        val list = brokerageService.getPendingSettlements(userId(token)).map { it.toResponse() }
        return ResponseEntity.ok(list)
    }

    // ── 변환 ────────────────────────────────────────────────────────────────────

    private fun BrokerageAccount.toResponse() = AccountResponse(
        id            = id,
        provider      = provider.name,
        accountNumber = accountNumber,
        accountType   = accountType.name,
        isActive      = isActive,
        connectedAt   = connectedAt,
        tokenValid    = isTokenValid(),
    )

    private fun BrokerageOrder.toResponse() = OrderResponse(
        id           = id,
        symbol       = symbol,
        side         = side.name,
        orderType    = orderType.name,
        quantity     = quantity,
        limitPrice   = limitPrice,
        filledQty    = filledQty,
        avgFillPrice = avgFillPrice,
        pgOrderId    = pgOrderId,
        status       = status.name,
        rejectReason = rejectReason,
        submittedAt  = submittedAt,
        filledAt     = filledAt,
    )

    private fun BrokerageSettlement.toResponse() = SettlementResponse(
        id          = id,
        symbol      = symbol,
        side        = side,
        quantity    = quantity,
        fillPrice   = fillPrice,
        grossAmount = grossAmount,
        fee         = fee,
        tax         = tax,
        netAmount   = netAmount,
        settleDate  = settleDate,
        status      = status.name,
        settledAt   = settledAt,
    )
}
