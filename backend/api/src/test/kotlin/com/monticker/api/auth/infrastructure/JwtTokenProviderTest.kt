package com.monticker.api.auth.infrastructure

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Date

private const val TEST_SECRET = "test-secret-key-that-is-at-least-32-bytes!!"

class JwtTokenProviderTest {

    private val provider = JwtTokenProvider(
        secret = TEST_SECRET,
        accessTokenExpiryMs = 900_000L,
        refreshTokenExpiryMs = 604_800_000L,
    )

    @Test
    fun `access token contains userId, email, role`() {
        val token = provider.generateAccessToken(1L, "user@test.com", "USER")
        assertThat(provider.validateToken(token)).isTrue()
        assertThat(provider.getUserId(token)).isEqualTo(1L)
        assertThat(provider.getEmail(token)).isEqualTo("user@test.com")
        assertThat(provider.getRole(token)).isEqualTo("USER")
    }

    @Test
    fun `refresh token subject is userId`() {
        val token = provider.generateRefreshToken(42L)
        assertThat(provider.validateToken(token)).isTrue()
        assertThat(provider.getUserId(token)).isEqualTo(42L)
    }

    @Test
    fun `tampered token fails validation`() {
        val token = provider.generateAccessToken(1L, "user@test.com", "USER")
        assertThat(provider.validateToken(token + "tampered")).isFalse()
    }

    @Test
    fun `expired token fails validation`() {
        val shortLived = JwtTokenProvider(
            secret = "test-secret-key-that-is-at-least-32-bytes!!",
            accessTokenExpiryMs = 1L,
            refreshTokenExpiryMs = 1L,
        )
        val token = shortLived.generateAccessToken(1L, "user@test.com", "USER")
        Thread.sleep(10)
        assertThat(shortLived.validateToken(token)).isFalse()
    }

    @Test
    fun `a correctly-signed token missing the issuer aud claims is rejected`() {
        // L2 — 같은 비밀키로 서명됐더라도 이 서비스가 발급하지 않은(iss/aud 없는) 토큰은
        // 거부해야 한다. requireIssuer/requireAudience 검증이 실제로 걸리는지 확인.
        val key = Keys.hmacShaKeyFor(TEST_SECRET.toByteArray(Charsets.UTF_8))
        val now = Date()
        val foreignToken = Jwts.builder()
            .subject("1")
            .claim("email", "user@test.com")
            .claim("role", "USER")
            .issuedAt(now)
            .expiration(Date(now.time + 900_000L))
            .signWith(key)
            .compact()

        assertThat(provider.validateToken(foreignToken)).isFalse()
    }
}
