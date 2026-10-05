package com.monticker.api.risk.api

import com.monticker.api.risk.application.RiskDecisionPage
import com.monticker.api.risk.application.RiskDecisionQueryService
import com.monticker.api.risk.application.RiskDecisionSummary
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 리스크 게이트 차단 기록. 사용자 식별은 인증 주체에서만 한다 — 경로·쿼리로 다른 사용자를 지정할 수 없다.
 * (`/api/risk/limits`·`/exposure`는 matching.api.RiskController에 있다 — ADR-025 분리 이전부터의 위치.)
 */
@RestController
@RequestMapping("/api/risk/decisions")
class RiskDecisionController(private val decisions: RiskDecisionQueryService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<RiskDecisionPage> = ResponseEntity.ok(decisions.blocked(userId(), page, size))

    @GetMapping("/summary")
    fun summary(): ResponseEntity<RiskDecisionSummary> = ResponseEntity.ok(decisions.summary(userId()))
}
