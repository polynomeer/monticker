package com.monticker.api.auth.api

import com.monticker.api.auth.application.UserProfile
import com.monticker.api.auth.application.UserProfileService
import com.monticker.api.auth.domain.User
import com.monticker.api.auth.infrastructure.UserRepository
import com.monticker.api.common.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Instant
import java.util.Optional

/** GET /api/users/me — 토큰 주체의 프로필만, 내부 값은 내보내지 않고, 탈퇴 계정은 404 */
class UserProfileControllerTest {
    private val repo = mockk<UserRepository>()
    private val service = UserProfileService(repo)

    private val principal = object : HandlerMethodArgumentResolver {
        override fun supportsParameter(p: MethodParameter) =
            p.parameterType == Long::class.java && p.hasParameterAnnotation(AuthenticationPrincipal::class.java)
        override fun resolveArgument(p: MethodParameter, m: ModelAndViewContainer?, r: NativeWebRequest, b: WebDataBinderFactory?): Any = 42L
    }

    private val mvc = MockMvcBuilders.standaloneSetup(UserProfileController(service))
        .setCustomArgumentResolvers(principal)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun user(deleted: Boolean = false) = User(
        id = 42L, email = "demo@monticker.dev", passwordHash = "hash", nickname = "데모", provider = "LOCAL",
        providerId = "pid", emailVerified = true, createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    ).also { if (deleted) it.anonymize() }

    @Test
    fun `returns the token user's profile without internal fields`() {
        every { repo.findById(42L) } returns Optional.of(user())

        mvc.perform(get("/api/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("demo@monticker.dev"))
            .andExpect(jsonPath("$.nickname").value("데모"))
            .andExpect(jsonPath("$.provider").value("LOCAL"))
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.providerId").doesNotExist())
    }

    @Test
    fun `missing or deleted user is 404`() {
        every { repo.findById(42L) } returns Optional.empty()
        mvc.perform(get("/api/users/me")).andExpect(status().isNotFound)

        every { repo.findById(42L) } returns Optional.of(user(deleted = true))
        mvc.perform(get("/api/users/me")).andExpect(status().isNotFound)
    }

    @Test
    fun `service rejects a deleted account directly`() {
        every { repo.findById(7L) } returns Optional.of(user(deleted = true))
        assertThatThrownBy { service.get(7L) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
