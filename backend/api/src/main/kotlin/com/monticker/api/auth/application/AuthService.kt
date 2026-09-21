package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import org.slf4j.LoggerFactory
import com.monticker.api.common.redis.RedisGuard
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val VERIFY_PREFIX = "email:verify:"
private const val RESET_PREFIX  = "pwd:reset:"
private const val VERIFY_TTL_H  = 24L
private const val RESET_TTL_MIN = 30L

// 로그인 실패 잠금 — /login은 인증 전 엔드포인트라 @RateLimited(userId 기반)를 못 쓰고, IP 기반
// RateLimitFilter만으로는 여러 IP에 분산된 크리덴셜 스터핑을 못 막는다. 이메일 단위로 실패
// 횟수를 세어 그 계정만 잠근다 — 성공하면 즉시 리셋되므로 정상 사용자는 영향받지 않는다.
private const val LOGIN_FAIL_PREFIX = "auth:login:fail:"
private const val LOGIN_FAIL_LIMIT  = 5
private val LOGIN_FAIL_WINDOW: Duration = Duration.ofMinutes(15)

@Service
@Transactional
class AuthService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val emailService: EmailService,
    private val guard: RedisGuard,
    private val revocationService: RefreshTokenRevocationService,
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
        // 인증 메일은 가입의 부수 효과다. Redis(토큰 저장소)가 없다고 가입 자체를 500으로 실패시키지
        // 않는다 — 계정과 토큰은 발급하고, 메일은 /resend-verification으로 나중에 받을 수 있다.
        // CH-01 실험에서 Redis 정지 중 가입이 500으로 떨어지는 것을 확인해 fail-open으로 바꿨다 (P0-1).
        guard.failOpen(op = "signup_verify_token", fallback = Unit) { sendVerificationEmail(user) }
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

    // forgotPassword와 같은 이유로 이메일 존재 여부·인증 상태를 노출하지 않는다(H4) — 등록되지
    // 않았거나 이미 인증된 이메일이면 조용히 아무 일도 하지 않는다. 컨트롤러는 항상 같은
    // 메시지를 반환한다.
    fun resendVerification(email: String) {
        val user = userRepository.findByEmail(email).orElse(null) ?: return
        if (user.emailVerified) return
        sendVerificationEmail(user)
    }

    fun login(email: String, password: String): TokenPair {
        val failKey = LOGIN_FAIL_PREFIX + email
        // 실패 카운터는 브루트포스 방어다 — Redis가 없다고 로그인 자체를 막지 않는다 (fail-open, P0-1).
        // IP 기반 1차 방어(RateLimitFilter)가 따로 있다.
        val fails = guard.failOpen(op = "login_fail_get", fallback = 0) {
            redis.opsForValue().get(failKey)?.toIntOrNull() ?: 0
        }
        require(fails < LOGIN_FAIL_LIMIT) { "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요." }

        try {
            val user = userRepository.findByEmail(email)
                .orElseThrow { IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.") }
            require(!user.isDeleted) { "탈퇴한 계정입니다." }
            require(passwordEncoder.matches(password, user.passwordHash)) {
                "이메일 또는 비밀번호가 올바르지 않습니다."
            }
            guard.failOpen(op = "login_fail_reset", fallback = Unit) { redis.delete(failKey); Unit }
            return issueTokens(user)
        } catch (e: IllegalArgumentException) {
            guard.failOpen(op = "login_fail_incr", fallback = Unit) {
                redis.opsForValue().increment(failKey)
                redis.expire(failKey, LOGIN_FAIL_WINDOW)
                Unit
            }
            throw e
        }
    }

    fun refresh(refreshToken: String): TokenPair {
        val userId = try {
            jwtTokenProvider.getUserId(refreshToken)
        } catch (e: Exception) {
            throw IllegalArgumentException("유효하지 않은 refresh token입니다.")
        }
        val tokenHash = hashToken(refreshToken)
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? AND user_id = ? AND expires_at > now()",
            Int::class.java, tokenHash, userId,
        ) ?: 0
        if (count == 0) {
            // 서명은 유효한데 DB엔 없다 — 정상 흐름이라면 있을 수 없다(로그아웃했거나, 이미
            // 회전으로 폐기된 토큰을 다시 쓴 것). 탈취된 토큰의 재사용 가능성으로 보고 이
            // 사용자의 모든 refresh token을 폐기해 전체 세션을 강제 종료한다(H3). 정상적인
            // 동시 탭 경쟁 상황도 이 경로를 탈 수 있지만, 재로그인 한 번으로 복구되는 비용이
            // 탈취 토큰을 계속 살려두는 위험보다 작다. 곧바로 던지는 예외가 이 메서드의
            // 트랜잭션을 롤백시키므로, 폐기는 REQUIRES_NEW로 별도 커밋한다.
            revocationService.revokeAll(userId)
            throw IllegalArgumentException("만료되었거나 유효하지 않은 refresh token입니다.")
        }
        jdbc.update("DELETE FROM refresh_tokens WHERE token_hash = ?", tokenHash)
        val user = userRepository.findById(userId).orElseThrow()
        require(!user.isDeleted) { "탈퇴한 계정입니다." }
        return issueTokens(user)
    }

    /**
     * 이 refresh token 하나만 폐기한다(로그아웃한 기기만) — 비밀번호 재설정처럼 모든 기기를
     * 강제 로그아웃하지 않는다. 토큰이 이미 만료·사용됐거나 형식이 잘못돼도 조용히 무시한다
     * (로그아웃은 이미 목표 상태에 도달했으므로 실패로 취급할 이유가 없다).
     */
    fun logout(refreshToken: String) {
        val userId = runCatching { jwtTokenProvider.getUserId(refreshToken) }.getOrNull() ?: return
        jdbc.update(
            "DELETE FROM refresh_tokens WHERE token_hash = ? AND user_id = ?",
            hashToken(refreshToken), userId,
        )
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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

    fun socialLoginOrSignup(email: String, nickname: String, provider: String, providerId: String): TokenPair {
        val user = userRepository.findByEmail(email).orElse(null) ?: userRepository.save(
            User(
                email         = email,
                passwordHash  = "",
                nickname      = nickname,
                provider      = provider,
                providerId    = providerId,
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
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
            user.id, hashToken(refreshToken), Timestamp.from(expiresAt),
        )
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }
}

data class TokenPair(val accessToken: String, val refreshToken: String)
