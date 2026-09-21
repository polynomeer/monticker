package com.monticker.api.auth.application

import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.JwtTokenProvider
import com.monticker.api.auth.infrastructure.UserRepository
import com.monticker.api.common.redis.RedisGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private val guard = RedisGuard(SimpleMeterRegistry())
    private val revocationService = mockk<RefreshTokenRevocationService>(relaxed = true)
    private val service = AuthService(userRepository, provider, encoder, jdbc, redis, emailService, guard, revocationService)

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

    // CH-01 실험에서 발견: Redis 정지 중 가입이 500이었다. 인증 메일 토큰 저장은 가입의 부수 효과라
    // fail-open — 계정·토큰은 발급하고 메일만 건너뛴다(/resend-verification으로 나중에 가능).
    @Test
    fun `Redis가 없어도 가입은 성공하고 인증 메일만 건너뛴다`() {
        every { userRepository.existsByEmail("nored@test.com") } returns false
        every { userRepository.save(any()) } answers {
            firstArg<User>().apply {
                val f = User::class.java.getDeclaredField("id"); f.isAccessible = true; f.set(this, 2L)
            }
        }
        every { valueOps.set(any<String>(), any<String>(), any<java.time.Duration>()) } throws
            org.springframework.data.redis.RedisConnectionFailureException("down")

        val result = service.signup("nored@test.com", "password1!", "무레디스")

        assertThat(result.accessToken).isNotBlank()
        verify(exactly = 0) { emailService.sendVerificationEmail(any(), any()) }
    }

    // 가입 직후(같은 초 안에) 로그인하면 두 refresh token이 동일해 refresh_tokens.token_hash
    // UNIQUE 제약에 걸려 500이 났다. 각 발급이 서로 다른 해시를 INSERT하는지 확인한다.
    @Test
    fun `signup then immediate login stores two distinct refresh token hashes`() {
        val user = User(id = 3L, email = "fast@test.com", passwordHash = encoder.encode("password1!"), nickname = "빠른")
        every { userRepository.existsByEmail("fast@test.com") } returns false
        every { userRepository.save(any()) } returns user
        every { userRepository.findByEmail("fast@test.com") } returns Optional.of(user)
        val hashes = mutableListOf<String>()
        every {
            jdbc.update(match<String> { it.startsWith("INSERT INTO refresh_tokens") }, 3L, capture(hashes), any())
        } returns 1

        val signedUp = service.signup("fast@test.com", "password1!", "빠른")
        val loggedIn = service.login("fast@test.com", "password1!")

        assertThat(signedUp.refreshToken).isNotEqualTo(loggedIn.refreshToken)
        assertThat(hashes).hasSize(2)
        assertThat(hashes[0]).isNotEqualTo(hashes[1])
    }

    @Test
    fun `signup throws when email already exists`() {
        every { userRepository.existsByEmail("dup@test.com") } returns true

        assertThatThrownBy { service.signup("dup@test.com", "password1!", "중복") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("이메일")
    }

    // ── resendVerification — 이메일 존재/인증 여부를 노출하지 않는다(H4, forgotPassword와 동일 패턴) ──

    @Test
    fun `resendVerification sends a new email for an unverified user`() {
        val user = User(id = 1L, email = "u@test.com", passwordHash = "x", nickname = "유저", emailVerified = false)
        every { userRepository.findByEmail("u@test.com") } returns Optional.of(user)

        service.resendVerification("u@test.com")

        verify { emailService.sendVerificationEmail("u@test.com", any()) }
    }

    @Test
    fun `resendVerification silently no-ops for an email that doesn't exist`() {
        every { userRepository.findByEmail("nobody@test.com") } returns Optional.empty()

        service.resendVerification("nobody@test.com")

        verify(exactly = 0) { emailService.sendVerificationEmail(any(), any()) }
    }

    @Test
    fun `resendVerification silently no-ops for an already-verified email`() {
        val user = User(id = 1L, email = "u@test.com", passwordHash = "x", nickname = "유저", emailVerified = true)
        every { userRepository.findByEmail("u@test.com") } returns Optional.of(user)

        service.resendVerification("u@test.com")

        verify(exactly = 0) { emailService.sendVerificationEmail(any(), any()) }
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
    fun `refresh reuse of an already-rotated token revokes every session for that user`() {
        // H3 — a validly-signed token with no matching DB row is either a stale rotated-out
        // token being replayed (possible theft) or a token from a logged-out session. Either
        // way, revoke the whole family instead of just rejecting this one request.
        val refreshToken = provider.generateRefreshToken(9L)
        every { jdbc.queryForObject(any<String>(), Int::class.java, any<String>(), 9L) } returns 0

        assertThatThrownBy { service.refresh(refreshToken) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify { revocationService.revokeAll(9L) }
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
