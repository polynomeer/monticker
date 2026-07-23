package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val VERIFY_PREFIX = "email:verify:"
private const val RESET_PREFIX  = "pwd:reset:"
private const val VERIFY_TTL_H  = 24L
private const val RESET_TTL_MIN = 30L

@Service
@Transactional
class AuthService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val emailService: EmailService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun signup(email: String, password: String, nickname: String): TokenPair {
        require(!userRepository.existsByEmail(email)) { "이미 사용 중인 이메일입니다." }
        val user = userRepository.save(
            User(
                email        = email,
                passwordHash = passwordEncoder.encode(password),
                nickname     = nickname,
            )
        )
        sendVerificationEmail(user)
        return issueTokens(user)
    }

    fun verifyEmail(token: String) {
        val userId = redis.opsForValue().get("$VERIFY_PREFIX$token")
            ?: throw IllegalArgumentException("유효하지 않거나 만료된 인증 링크입니다.")
        val user = userRepository.findById(userId.toLong())
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        user.emailVerified = true
        user.updatedAt = Instant.now()
        redis.delete("$VERIFY_PREFIX$token")
    }

    fun resendVerification(email: String) {
        val user = userRepository.findByEmail(email)
            .orElseThrow { IllegalArgumentException("등록되지 않은 이메일입니다.") }
        require(!user.emailVerified) { "이미 인증된 이메일입니다." }
        sendVerificationEmail(user)
    }

    fun login(email: String, password: String): TokenPair {
        val user = userRepository.findByEmail(email)
            .orElseThrow { IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.") }
        require(!user.isDeleted) { "탈퇴한 계정입니다." }
        require(passwordEncoder.matches(password, user.passwordHash)) {
            "이메일 또는 비밀번호가 올바르지 않습니다."
        }
        return issueTokens(user)
    }

    fun refresh(refreshToken: String): TokenPair {
        val userId = try {
            jwtTokenProvider.getUserId(refreshToken)
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 refresh token입니다.")
        }
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE token = ? AND user_id = ? AND expires_at > now()",
            Int::class.java, refreshToken, userId,
        ) ?: 0
        require(count > 0) { "만료되었거나 유효하지 않은 refresh token입니다." }
        jdbc.update("DELETE FROM refresh_tokens WHERE token = ?", refreshToken)
        val user = userRepository.findById(userId).orElseThrow()
        require(!user.isDeleted) { "탈퇴한 계정입니다." }
        return issueTokens(user)
    }

    fun forgotPassword(email: String) {
        // 이메일 존재 여부 노출 방지 — 항상 동일한 응답
        val user = userRepository.findByEmail(email).orElse(null) ?: return
        if (user.isDeleted) return
        val token = UUID.randomUUID().toString()
        redis.opsForValue().set("$RESET_PREFIX$token", user.id.toString(), Duration.ofMinutes(RESET_TTL_MIN))
        emailService.sendPasswordResetEmail(email, token)
        log.info("[AuthService] 비밀번호 재설정 메일 발송: userId={}", user.id)
    }

    fun resetPassword(token: String, newPassword: String) {
        require(newPassword.length >= 8) { "비밀번호는 8자 이상이어야 합니다." }
        val userId = redis.opsForValue().get("$RESET_PREFIX$token")
            ?: throw IllegalArgumentException("유효하지 않거나 만료된 링크입니다.")
        val user = userRepository.findById(userId.toLong()).orElseThrow()
        user.passwordHash = passwordEncoder.encode(newPassword)
        user.updatedAt = Instant.now()
        redis.delete("$RESET_PREFIX$token")
        // 다른 기기 강제 로그아웃
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", user.id)
        log.info("[AuthService] 비밀번호 재설정 완료: userId={}", user.id)
    }

    fun deleteAccount(userId: Long, password: String) {
        val user = userRepository.findById(userId).orElseThrow()
        require(passwordEncoder.matches(password, user.passwordHash)) { "비밀번호가 올바르지 않습니다." }
        user.deletedAt = Instant.now()
        user.updatedAt = Instant.now()
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId)
        log.info("[AuthService] 계정 삭제(soft): userId={}", userId)
    }

    fun socialLoginOrSignup(email: String, nickname: String): TokenPair {
        val user = userRepository.findByEmail(email).orElse(null) ?: userRepository.save(
            User(
                email        = email,
                passwordHash = "",
                nickname     = nickname,
                emailVerified = true,
            )
        )
        require(!user.isDeleted) { "삭제된 계정입니다." }
        return issueTokens(user)
    }

    private fun sendVerificationEmail(user: User) {
        val token = UUID.randomUUID().toString()
        redis.opsForValue().set("$VERIFY_PREFIX$token", user.id.toString(), Duration.ofHours(VERIFY_TTL_H))
        emailService.sendVerificationEmail(user.email, token)
    }

    private fun issueTokens(user: User): TokenPair {
        val accessToken  = jwtTokenProvider.generateAccessToken(user.id, user.email, user.role.name)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id)
        val expiresAt    = Instant.now().plusMillis(jwtTokenProvider.refreshTokenExpiryMs())
        jdbc.update(
            "INSERT INTO refresh_tokens (user_id, token, expires_at) VALUES (?, ?, ?)",
            user.id, refreshToken, Timestamp.from(expiresAt),
        )
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }
}

data class TokenPair(val accessToken: String, val refreshToken: String)
