package com.monticker.api.auth.api

import com.monticker.api.auth.application.AuthService
import com.monticker.api.auth.application.TokenPair
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@Validated
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: SignupRequest): ResponseEntity<SignupResponse> {
        val tokens = authService.signup(req.email, req.password, req.nickname)
        return ResponseEntity.ok(SignupResponse(
            accessToken  = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            emailVerificationSent = true,
        ))
    }

    @PostMapping("/verify-email")
    fun verifyEmail(@RequestParam token: String): ResponseEntity<MessageResponse> {
        authService.verifyEmail(token)
        return ResponseEntity.ok(MessageResponse("이메일 인증이 완료되었습니다."))
    }

    @PostMapping("/resend-verification")
    fun resendVerification(@RequestBody req: EmailRequest): ResponseEntity<MessageResponse> {
        authService.resendVerification(req.email)
        return ResponseEntity.ok(MessageResponse("인증 이메일을 다시 발송했습니다."))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ResponseEntity<TokenResponse> {
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

    @PostMapping("/forgot-password")
    fun forgotPassword(@RequestBody req: EmailRequest): ResponseEntity<MessageResponse> {
        authService.forgotPassword(req.email)
        // 이메일 존재 여부 노출 방지 — 항상 동일 메시지
        return ResponseEntity.ok(MessageResponse("등록된 이메일이라면 재설정 링크를 발송했습니다."))
    }

    @PostMapping("/reset-password")
    fun resetPassword(@RequestBody req: ResetPasswordRequest): ResponseEntity<MessageResponse> {
        authService.resetPassword(req.token, req.newPassword)
        return ResponseEntity.ok(MessageResponse("비밀번호가 변경되었습니다. 다시 로그인해주세요."))
    }

    @DeleteMapping("/account")
    fun deleteAccount(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody req: DeleteAccountRequest,
    ): ResponseEntity<MessageResponse> {
        val token  = authorization.removePrefix("Bearer ").trim()
        val userId = jwtTokenProvider.getUserId(token)
        authService.deleteAccount(userId, req.password)
        return ResponseEntity.ok(MessageResponse("계정이 삭제되었습니다."))
    }
}

data class SignupRequest(
    @field:Email @field:NotBlank val email: String,
    @field:Size(min = 8, max = 100) @field:NotBlank val password: String,
    @field:NotBlank @field:Size(min = 2, max = 30) val nickname: String,
)
data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)
data class RefreshRequest(@field:NotBlank val refreshToken: String)
data class EmailRequest(@field:Email @field:NotBlank val email: String)
data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:Size(min = 8, max = 100) @field:NotBlank val newPassword: String,
)
data class DeleteAccountRequest(@field:NotBlank val password: String)

data class TokenResponse(val accessToken: String, val refreshToken: String)
data class SignupResponse(val accessToken: String, val refreshToken: String, val emailVerificationSent: Boolean)
data class MessageResponse(val message: String)

private fun TokenPair.toResponse() = TokenResponse(accessToken, refreshToken)
