package com.monticker.api.risk.api

import com.monticker.api.common.aop.RateLimited
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * 미리보기 요청. 수량·가격을 BigDecimal로 받는 이유: Jackson 기본 설정은 `1.5`를 Int 필드에 넣으면 조용히 `1`로 자른다
 * (ACCEPT_FLOAT_AS_INT). 미리보기가 "1주로 점검했는데 화면엔 1.5주"가 되지 않게 정수 여부를 여기서 직접 본다.
 */
data class RiskPreviewRequest(
    val stockId: Long? = null,
    val side: String? = null,
    val quantity: BigDecimal? = null,
    /** 지정가. null 또는 0이면 시장가 — 서버가 최근가로 판정한다(없으면 규칙이 보수적으로 거부). */
    val estimatedPrice: BigDecimal? = null,
) {
    /** 검증을 통과한 값. 규칙은 POST /api/risk/check와 같고, 가격 음수·소수 수량·범위 밖 값을 추가로 거부한다. */
    fun validated(): Validated {
        val id = stockId
        require(id != null && id > 0) { "stockId가 필요합니다." }
        val s = side?.takeIf { it == "BUY" || it == "SELL" } ?: throw IllegalArgumentException("side는 BUY 또는 SELL이어야 합니다.")
        val q = quantity ?: throw IllegalArgumentException("수량이 필요합니다.")
        require(q.signum() > 0) { "수량은 0보다 커야 합니다." }
        require(q.stripTrailingZeros().scale() <= 0) { "수량은 정수여야 합니다." }
        require(q <= MAX_QUANTITY) { "수량이 너무 큽니다(최대 ${MAX_QUANTITY.toPlainString()}주)." }
        val price = estimatedPrice ?: BigDecimal.ZERO
        require(price.signum() >= 0) { "가격은 0 이상이어야 합니다." }
        require(price <= MAX_PRICE) { "가격이 너무 큽니다." }
        return Validated(id, s, q.intValueExact(), price)
    }

    data class Validated(val stockId: Long, val side: String, val quantity: Int, val estimatedPrice: BigDecimal)

    companion object {
        /** 웹 주문 폼의 상한(V-L7)과 같다. */
        val MAX_QUANTITY = BigDecimal(1_000_000)
        val MAX_PRICE = BigDecimal("1000000000000")
    }
}

/**
 * ADR-092 — 주문 전 리스크 체크 미리보기. 주문 폼이 입력할 때마다(웹 디바운스 400ms) 부른다.
 *
 * - **부수 효과 없음**: 감사 행·메트릭·주문·예약 어느 것도 만들지 않는다([RiskCheckerService.preview]). 이 응답으로는 아무것도
 *   주문할 수 없다 — 주문 경로는 제출 시점에 게이트를 다시 돌고 그때 감사 기록을 남긴다.
 * - 사용자 식별은 인증 주체에서만. 남의 계좌·한도를 지정하는 파라미터가 없다.
 * - 판정 비용(보유·VaR 조회)이 주문과 같아서 사용자당 분당 60회로 제한한다. 디바운스한 타이핑은 이 안에 들어온다.
 */
@RestController
@RequestMapping("/api/risk")
class RiskPreviewController(private val riskChecker: RiskCheckerService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @RateLimited(limit = 60, windowSec = 60, keyPrefix = "risk.preview")
    @PostMapping("/preview")
    fun preview(@RequestBody req: RiskPreviewRequest): ResponseEntity<RiskCheckResult> {
        val v = req.validated()
        return ResponseEntity.ok(riskChecker.preview(userId(), v.stockId, v.side, v.quantity, v.estimatedPrice))
    }
}
