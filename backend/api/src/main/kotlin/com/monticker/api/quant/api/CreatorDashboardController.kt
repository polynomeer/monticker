package com.monticker.api.quant.api

import com.monticker.api.quant.application.CreatorDashboard
import com.monticker.api.quant.application.CreatorDashboardService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 제작자 수익 대시보드 집계 — 로그인 사용자 본인의 공유 전략만. */
@RestController
@RequestMapping("/api/quant/market/creator")
class CreatorDashboardController(private val service: CreatorDashboardService) {

    private fun userId(): Long = SecurityContextHolder.getContext().authentication.principal as Long

    @GetMapping("/dashboard")
    fun dashboard(): ResponseEntity<CreatorDashboard> = ResponseEntity.ok(service.dashboard(userId()))
}
