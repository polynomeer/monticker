package com.monticker.api.auth.infrastructure

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * 다른 모듈에서 JWT로부터 사용자 식별자를 추출할 때 사용하는 auth 모듈의 공개 API.
 * auth.infrastructure의 나머지 타입(OAuth2/필터 내부 구현)은 auth 모듈 내부로 한정된다.
 */
@NamedInterface("api")
@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.access-token-expiry-ms:900000}") private val accessTokenExpiryMs: Long,
    @Value("\${jwt.refresh-token-expiry-ms:604800000}") private val refreshTokenExpiryMs: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun generateAccessToken(userId: Long, email: String, role: String): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .claim("role", role)
            .issuedAt(Date(now))
            .expiration(Date(now + accessTokenExpiryMs))
            .signWith(key)
            .compact()
    }

    fun generateRefreshToken(userId: Long): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date(now))
            .expiration(Date(now + refreshTokenExpiryMs))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return runCatching { parseClaims(token) }.isSuccess
    }

    fun getUserId(token: String): Long =
        parseClaims(token).subject.toLong()

    fun getEmail(token: String): String =
        parseClaims(token)["email"] as String

    fun getRole(token: String): String =
        parseClaims(token)["role"] as String

    fun refreshTokenExpiryMs(): Long = refreshTokenExpiryMs

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
