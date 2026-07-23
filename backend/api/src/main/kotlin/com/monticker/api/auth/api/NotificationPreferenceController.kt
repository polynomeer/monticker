package com.monticker.api.auth.api

import com.monticker.api.auth.infrastructure.JwtTokenProvider
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.data.redis.core.StringRedisTemplate
import com.fasterxml.jackson.databind.ObjectMapper

data class NotificationPreferenceRequest(
    val pushEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val priceAlertPush: Boolean = true,
    val priceAlertEmail: Boolean = false,
    val newsAlertPush: Boolean = true,
    val newsAlertEmail: Boolean = false,
    val weeklyReportEmail: Boolean = true,
)

@Validated
@RestController
@RequestMapping("/api/users/me")
class NotificationPreferenceController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private fun prefKey(userId: Long) = "notif:pref:$userId"

    @GetMapping("/notification-preferences")
    fun get(@RequestHeader("Authorization") auth: String): ResponseEntity<Any> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
        val json = redis.opsForValue().get(prefKey(userId))
        val pref = if (json != null) objectMapper.readValue(json, NotificationPreferenceRequest::class.java)
                   else NotificationPreferenceRequest()
        return ResponseEntity.ok(pref)
    }

    @PutMapping("/notification-preferences")
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: NotificationPreferenceRequest,
    ): ResponseEntity<NotificationPreferenceRequest> {
        val userId = jwtTokenProvider.getUserId(auth.removePrefix("Bearer ").trim())
        redis.opsForValue().set(prefKey(userId), objectMapper.writeValueAsString(body))
        return ResponseEntity.ok(body)
    }
}
