package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
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
    // mockk의 relaxed 모드는 ValueOperations<K, V>처럼 제네릭 반환 타입인 인터페이스에서 null 대신
    // 임의 Object를 만들어내는 경우가 있다 — get()이 String이 아닌 값을 반환하면 AuthService.login의
    // `?.toIntOrNull()`에서 ClassCastException이 난다. 기본값을 null로 명시해 두고, 잠금 관련
    // 테스트에서만 개별적으로 값을 덮어쓴다.
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true).also {
        every { it.get(any<String>()) } returns null
    }
    private val redis = mockk<StringRedisTemplate>(relaxed = true).also {
        every { it.opsForValue() } returns valueOps
    }
    private val emailService = mockk<EmailService>(relaxed = true)
    private val service = AuthService(userRepository, provider, encoder, jdbc, redis, emailService)

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

    @Test
    fun `login is locked out once the per-email failure count reaches the limit, without even querying the user`() {
        every { valueOps.get("auth:login:fail:locked@test.com") } returns "5"

        assertThatThrownBy { service.login("locked@test.com", "whatever") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("로그인 시도")
        verify(exactly = 0) { userRepository.findByEmail(any()) }
    }

    @Test
    fun `login records a failed attempt on wrong password so it counts toward the lockout`() {
        val user = User(id = 1L, email = "u2@test.com", passwordHash = encoder.encode("correct"), nickname = "유저2")
        every { userRepository.findByEmail("u2@test.com") } returns Optional.of(user)

        assertThatThrownBy { service.login("u2@test.com", "wrong") }.isInstanceOf(IllegalArgumentException::class.java)

        verify { valueOps.increment("auth:login:fail:u2@test.com") }
    }

    @Test
    fun `login resets the failure counter on success so a normal user is never affected`() {
        val hashed = encoder.encode("pass1234!")
        val user = User(id = 1L, email = "u3@test.com", passwordHash = hashed, nickname = "유저3")
        every { userRepository.findByEmail("u3@test.com") } returns Optional.of(user)

        service.login("u3@test.com", "pass1234!")

        verify { redis.delete("auth:login:fail:u3@test.com") }
    }

    // ── refresh / logout — refresh_tokens는 이제 평문이 아니라 SHA-256 해시로 저장/조회된다 ──

    @Test
    fun `refresh accepts a token matching the stored hash and rotates it for a new pair`() {
        val user = User(id = 5L, email = "r@test.com", passwordHash = "x", nickname = "리프레시")
        val refreshToken = provider.generateRefreshToken(5L)
        every {
            jdbc.queryForObject(
                match<String> { it.contains("refresh_tokens") && it.contains("token_hash") },
                Int::class.java, any<String>(), 5L,
            )
        } returns 1
        every { userRepository.findById(5L) } returns Optional.of(user)

        val result = service.refresh(refreshToken)

        assertThat(result.accessToken).isNotBlank()
        assertThat(result.refreshToken).isNotBlank()
        // 삭제는 원문 토큰이 아니라 해시로 매칭되어야 한다 — DB가 유출돼도 저장값에서 원문을 복원할 수 없어야 한다.
        verify { jdbc.update(match<String> { it.startsWith("DELETE FROM refresh_tokens WHERE token_hash") }, any<String>()) }
        verify(exactly = 0) { jdbc.update(any<String>(), eq(refreshToken)) }
    }

    @Test
    fun `refresh rejects a token whose hash has no matching row`() {
        val refreshToken = provider.generateRefreshToken(9L)
        every { jdbc.queryForObject(any<String>(), Int::class.java, any<String>(), 9L) } returns 0

        assertThatThrownBy { service.refresh(refreshToken) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `refresh rejects a garbage token without querying the database`() {
        assertThatThrownBy { service.refresh("not-a-jwt") }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Int::class.java, any<String>(), any<Long>()) }
    }

    @Test
    fun `logout deletes only the presenting user's matching token row`() {
        val refreshToken = provider.generateRefreshToken(7L)

        service.logout(refreshToken)

        verify {
            jdbc.update(
                match<String> { it.startsWith("DELETE FROM refresh_tokens WHERE token_hash") && it.contains("user_id") },
                any<String>(), 7L,
            )
        }
    }

    @Test
    fun `logout on a garbage token is a silent no-op instead of throwing`() {
        service.logout("not-a-jwt-at-all")

        verify(exactly = 0) { jdbc.update(any<String>(), any(), any()) }
    }
}
