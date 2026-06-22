package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

@Service
@Transactional
class AuthService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
) {
    fun signup(email: String, password: String, nickname: String): TokenPair {
        require(!userRepository.existsByEmail(email)) { "이미 사용 중인 이메일입니다." }
        val user = userRepository.save(
            User(
                email        = email,
                passwordHash = passwordEncoder.encode(password),
                nickname     = nickname,
            )
        )
        return issueTokens(user)
    }

    fun login(email: String, password: String): TokenPair {
        val user = userRepository.findByEmail(email)
            .orElseThrow { IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.") }
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
            Int::class.java,
            refreshToken, userId,
        ) ?: 0
        require(count > 0) { "만료되었거나 유효하지 않은 refresh token입니다." }

        // rotate: delete old, issue new
        jdbc.update("DELETE FROM refresh_tokens WHERE token = ?", refreshToken)
        val user = userRepository.findById(userId).orElseThrow()
        return issueTokens(user)
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
