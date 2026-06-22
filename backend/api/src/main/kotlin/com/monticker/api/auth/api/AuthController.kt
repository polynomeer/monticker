package com.monticker.api.auth.api

import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.application.TokenPair
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/signup")
    fun signup(@RequestBody req: SignupRequest): ResponseEntity<TokenResponse> {
        require(req.email.isNotBlank() && req.password.length >= 8 && req.nickname.isNotBlank()) {
            "입력값이 올바르지 않습니다."
        }
        return ResponseEntity.ok(authService.signup(req.email, req.password, req.nickname).toResponse())
    }

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<TokenResponse> {
        return try {
            ResponseEntity.ok(authService.login(req.email, req.password).toResponse())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(401).build()
        }
    }

    @PostMapping("/refresh")
    fun refresh(@RequestBody req: RefreshRequest): ResponseEntity<TokenResponse> {
        return try {
            ResponseEntity.ok(authService.refresh(req.refreshToken).toResponse())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(401).build()
        }
    }
}

data class SignupRequest(val email: String, val password: String, val nickname: String)
data class LoginRequest(val email: String, val password: String)
data class RefreshRequest(val refreshToken: String)
data class TokenResponse(val accessToken: String, val refreshToken: String)

private fun TokenPair.toResponse() = TokenResponse(accessToken, refreshToken)
