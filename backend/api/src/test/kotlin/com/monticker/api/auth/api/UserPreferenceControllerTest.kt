package com.monticker.api.auth.api

import com.monticker.api.auth.application.InterestSector
import com.monticker.api.auth.application.UsageStyle
import com.monticker.api.auth.application.UserPreferenceService
import com.monticker.api.auth.application.UserPreferences
import com.monticker.api.common.exception.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** ADR-089 — PUT/GET /api/users/me/preferences: 화이트리스트, 길이 상한, 중복 제거, 토큰 주체만. */
class UserPreferenceControllerTest {
    private val service = mockk<UserPreferenceService>()

    private val principal = object : HandlerMethodArgumentResolver {
        override fun supportsParameter(p: MethodParameter) =
            p.parameterType == Long::class.java && p.hasParameterAnnotation(AuthenticationPrincipal::class.java)
        override fun resolveArgument(p: MethodParameter, m: ModelAndViewContainer?, r: NativeWebRequest, b: WebDataBinderFactory?): Any = 42L
    }

    private val mvc = MockMvcBuilders.standaloneSetup(UserPreferenceController(service))
        .setCustomArgumentResolvers(principal)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun putJson(body: String) = mvc.perform(put("/api/users/me/preferences").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `GET returns the stored preferences of the token user`() {
        every { service.get(42L) } returns UserPreferences(listOf(InterestSector.BIO), UsageStyle.QUANT)

        mvc.perform(get("/api/users/me/preferences"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interestSectors[0]").value("BIO"))
            .andExpect(jsonPath("$.usageStyle").value("QUANT"))
    }

    @Test
    fun `PUT saves whitelisted values for the token user, deduplicated in order, ignoring a body userId`() {
        every { service.save(42L, any(), any()) } returns UserPreferences(listOf(InterestSector.ETF, InterestSector.BIO), UsageStyle.OBSERVE)

        putJson("""{"userId":1,"interestSectors":["ETF","BIO","ETF"],"usageStyle":"OBSERVE"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.interestSectors.length()").value(2))

        verify(exactly = 1) { service.save(42L, listOf(InterestSector.ETF, InterestSector.BIO), UsageStyle.OBSERVE) }
        verify(exactly = 0) { service.save(1L, any(), any()) }
    }

    @Test
    fun `PUT with empty selections clears them`() {
        every { service.save(42L, emptyList(), null) } returns UserPreferences()

        putJson("""{"interestSectors":[],"usageStyle":null}""").andExpect(status().isOk)
        verify { service.save(42L, emptyList(), null) }
    }

    @Test
    fun `unknown sector, screen label, unknown style or lowercase are 400 and nothing is saved`() {
        listOf(
            """{"interestSectors":["CRYPTO"]}""",
            """{"interestSectors":["반도체"]}""",
            """{"interestSectors":["bio"]}""",
            """{"interestSectors":[null]}""",
            """{"usageStyle":"DAY_TRADER"}""",
            """{"usageStyle":"<script>"}""",
        ).forEach { putJson(it).andExpect(status().isBadRequest) }
        verify(exactly = 0) { service.save(any(), any(), any()) }
    }

    @Test
    fun `oversized list is 400`() {
        val body = (1..21).joinToString(",", "[", "]") { "\"ETF\"" }
        putJson("""{"interestSectors":$body}""").andExpect(status().isBadRequest)
        verify(exactly = 0) { service.save(any(), any(), any()) }
    }

    @Test
    fun `parse keeps first-seen order and allows the max raw size`() {
        val (sectors, style) = UserPreferenceService.parse(List(20) { if (it % 2 == 0) "FINANCE" else "BIO" }, null)
        assertThat(sectors).containsExactly(InterestSector.FINANCE, InterestSector.BIO)
        assertThat(style).isNull()
        assertThatThrownBy { UserPreferenceService.parse(List(21) { "BIO" }, null) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
