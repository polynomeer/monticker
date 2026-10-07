package com.monticker.api.alert.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.alert.application.AlertService
import com.monticker.api.alert.domain.AlertRule
import com.monticker.api.alert.domain.AlertRuleType
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class AlertControllerTest {

    private val alertService = mockk<AlertService>()
    private val controller = AlertController(alertService)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    // AlertController.userId()는 SecurityContextHolder에서 principal을 꺼낸다 — standalone MockMvc는
    // 실제 시큐리티 필터 체인을 안 태우므로 직접 채워줘야 한다.
    @BeforeEach
    fun setUpSecurityContext() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(1L, null, emptyList())
    }

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    private fun makeRule(id: Long = 1L) = AlertRule(
        id = id, userId = 1L, stockId = 1L,
        ruleType = AlertRuleType.PRICE_ABOVE,
        conditionJson = """{"threshold":75000}""",
        createdAt = Instant.now(),
    )

    @Test
    fun `GET rules returns list`() {
        every { alertService.getRules(any()) } returns listOf(makeRule())

        mockMvc.perform(get("/api/alerts/rules"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].ruleType").value("PRICE_ABOVE"))
    }

    @Test
    fun `POST rules creates rule`() {
        every { alertService.createRule(any(), any(), any(), any()) } returns makeRule()

        val body = mapOf("stockId" to 1, "ruleType" to "PRICE_ABOVE", "condition" to mapOf("threshold" to 75000))

        mockMvc.perform(
            post("/api/alerts/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ruleType").value("PRICE_ABOVE"))
    }

    @Test
    fun `POST rules returns 400 for invalid ruleType`() {
        val body = mapOf("stockId" to 1, "ruleType" to "INVALID_TYPE", "condition" to emptyMap<String, Any>())

        mockMvc.perform(
            post("/api/alerts/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isBadRequest)
    }

    // backlog §9 — stock_id 없는 룰은 워커가 색인하지 않아 평생 안 울린다. 저장을 거부해야 한다.
    @Test
    fun `POST rules returns 400 when stockId is missing`() {
        val body = mapOf("ruleType" to "VOLUME_SURGE", "condition" to emptyMap<String, Any>())

        mockMvc.perform(post("/api/alerts/rules").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { alertService.createRule(any(), any(), any(), any()) }
    }

    // 평가기가 구현된 적 없는 타입 — "저장은 되는데 평생 안 울리는 룰"을 만들지 않는다
    @Test
    fun `POST rules returns 400 for rule types that nothing evaluates`() {
        for (type in listOf("NEWS_PUBLISHED", "DISCLOSURE_PUBLISHED")) {
            val body = mapOf("stockId" to 1, "ruleType" to type, "condition" to emptyMap<String, Any>())
            mockMvc.perform(post("/api/alerts/rules").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest)
        }
        verify(exactly = 0) { alertService.createRule(any(), any(), any(), any()) }
    }

    @Test
    fun `DELETE rule returns 204`() {
        justRun { alertService.deactivateRule(any(), any()) }

        mockMvc.perform(delete("/api/alerts/rules/1"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE rule returns 404 when not found`() {
        every { alertService.deactivateRule(any(), eq(99L)) } throws NoSuchElementException("not found")

        mockMvc.perform(delete("/api/alerts/rules/99"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET rules with includePaused passes the flag`() {
        every { alertService.getRules(1L, true) } returns listOf(makeRule())

        mockMvc.perform(get("/api/alerts/rules").param("includePaused", "true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(1))

        verify { alertService.getRules(1L, true) }
    }

    @Test
    fun `PATCH rule toggles active state`() {
        every { alertService.setActive(1L, 1L, false) } returns makeRule().apply { isActive = false }

        mockMvc.perform(patch("/api/alerts/rules/1").contentType(MediaType.APPLICATION_JSON).content("""{"isActive":false}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isActive").value(false))
    }

    @Test
    fun `PATCH rule without isActive is rejected`() {
        mockMvc.perform(patch("/api/alerts/rules/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { alertService.setActive(any(), any(), any()) }
    }

    @Test
    fun `read endpoints mark history as read for the caller`() {
        justRun { alertService.markRead(1L, 5L) }
        every { alertService.markAllRead(1L, any()) } returns 3

        mockMvc.perform(post("/api/alerts/history/5/read")).andExpect(status().isNoContent)
        mockMvc.perform(post("/api/alerts/history/read-all").param("upTo", "2099-01-01T00:00:00Z"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updated").value(3))

        // 미래 시각은 지금으로 잘라 화면을 연 뒤 들어올 알림까지 읽음 처리하지 않는다
        verify { alertService.markAllRead(1L, match { it.isBefore(Instant.now().plusSeconds(1)) }) }
    }
}
