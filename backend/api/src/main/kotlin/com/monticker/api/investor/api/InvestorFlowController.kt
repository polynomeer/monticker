package com.monticker.api.investor.api

import com.monticker.api.investor.application.InvestorFlowResult
import com.monticker.api.investor.application.InvestorFlowService
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class InvestorFlowController(private val investorFlowService: InvestorFlowService) {

    /**
     * 개인·외국인·기관 순매수 조회.
     *
     * GET /api/stocks/{stockId}/investor-flow?days=20
     */
    @GetMapping("/api/stocks/{stockId}/investor-flow")
    fun getInvestorFlow(
        @PathVariable stockId: Long,
        @RequestParam(defaultValue = "20") days: Int,
    ): ResponseEntity<InvestorFlowResult> =
        ResponseEntity.ok(investorFlowService.getFlow(stockId, days))
}
