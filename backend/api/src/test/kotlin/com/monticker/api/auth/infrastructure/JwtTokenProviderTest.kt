package com.monticker.api.auth.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val provider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-bytes!!",
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
}
