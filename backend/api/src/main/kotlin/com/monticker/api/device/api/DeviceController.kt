package com.monticker.api.device.api

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/devices")
class DeviceController(private val jdbc: JdbcTemplate) {

    @PostMapping("/push-token")
    fun registerToken(
        @RequestBody req: RegisterTokenRequest,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Void> {
        val uid = userId ?: return ResponseEntity.ok().build()

        jdbc.update(
            """
            INSERT INTO device_tokens (user_id, token, platform)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, token) DO UPDATE SET
                is_active  = true,
                platform   = EXCLUDED.platform,
                updated_at = now()
            """,
            uid,
            req.token.take(512),
            req.platform.take(20),
        )
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/push-token")
    fun unregisterToken(
        @RequestBody req: RegisterTokenRequest,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Void> {
        val uid = userId ?: return ResponseEntity.ok().build()
        jdbc.update(
            "UPDATE device_tokens SET is_active = false, updated_at = now() WHERE user_id = ? AND token = ?",
            uid, req.token,
        )
        return ResponseEntity.ok().build()
    }
}

data class RegisterTokenRequest(
    val token: String,
    val platform: String = "ios",
)
