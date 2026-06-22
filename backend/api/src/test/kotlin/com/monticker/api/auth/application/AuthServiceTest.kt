package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

class AuthServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val encoder = BCryptPasswordEncoder()
    private val provider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-bytes!!",
        accessTokenExpiryMs = 900_000L,
        refreshTokenExpiryMs = 604_800_000L,
    )
    private val service = AuthService(userRepository, provider, encoder, jdbc)

    @Test
    fun `signup creates user and returns tokens`() {
        every { userRepository.existsByEmail("test@test.com") } returns false
        every { userRepository.save(any()) } answers {
            firstArg<User>().apply {
                val f = User::class.java.getDeclaredField("id")
                f.isAccessible = true
                f.set(this, 1L)
            }
        }

        val result = service.signup("test@test.com", "password1!", "테스터")

        assertThat(result.accessToken).isNotBlank()
        assertThat(result.refreshToken).isNotBlank()
    }

    @Test
    fun `signup throws when email already exists`() {
        every { userRepository.existsByEmail("dup@test.com") } returns true

        assertThatThrownBy { service.signup("dup@test.com", "password1!", "중복") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("이메일")
    }

    @Test
    fun `login returns tokens for valid credentials`() {
        val hashed = encoder.encode("pass1234!")
        val user = User(id = 1L, email = "u@test.com", passwordHash = hashed, nickname = "유저")
        every { userRepository.findByEmail("u@test.com") } returns Optional.of(user)

        val result = service.login("u@test.com", "pass1234!")

        assertThat(result.accessToken).isNotBlank()
    }

    @Test
    fun `login throws for wrong password`() {
        val user = User(id = 1L, email = "u@test.com", passwordHash = encoder.encode("correct"), nickname = "유저")
        every { userRepository.findByEmail("u@test.com") } returns Optional.of(user)

        assertThatThrownBy { service.login("u@test.com", "wrong") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
