package com.monticker.api.paper.api

import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.application.TradeHistoryFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/** GET /api/paper/history — 종목·시각 필터의 검증과 사용자 범위(인증 주체만). */
class PaperHistoryControllerTest {
    private val queryService = mockk<PaperPortfolioQueryService>()
    private val controller = PaperController(mockk(), queryService, mockk(), mockk())
    private val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(GlobalExceptionHandler()).build()

    @BeforeEach
    fun auth() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(42L, null, emptyList())
        every { queryService.getHistory(any(), any(), any(), any()) } returns emptyList()
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `no filter keeps the old call shape — page, clamped size, no conditions`() {
        mvc.perform(get("/api/paper/history")).andExpect(status().isOk)
        verify { queryService.getHistory(42L, 0, 20, TradeHistoryFilter.NONE) }

        mvc.perform(get("/api/paper/history?page=2&size=500")).andExpect(status().isOk)
        verify { queryService.getHistory(42L, 2, 100, TradeHistoryFilter.NONE) }
    }

    @Test
    fun `stockId and range are passed through for the authenticated user only`() {
        mvc.perform(
            get("/api/paper/history?stockId=7&from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z&size=100&userId=1"),
        ).andExpect(status().isOk)

        verify(exactly = 1) {
            queryService.getHistory(
                42L, 0, 100,
                TradeHistoryFilter(7L, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
            )
        }
        verify(exactly = 0) { queryService.getHistory(1L, any(), any(), any()) }
    }

    @Test
    fun `invalid filters are 400 before reaching the service`() {
        listOf(
            "stockId=0",
            "stockId=-3",
            "stockId=abc",
            "from=yesterday",
            "to=2026-13-01T00:00:00Z",
            "from=2026-10-01T00:00:00Z&to=2026-10-01T00:00:00Z",
            "from=2026-10-02T00:00:00Z&to=2026-10-01T00:00:00Z",
            "page=-1",
        ).forEach { q ->
            mvc.perform(get("/api/paper/history?$q")).andExpect(status().isBadRequest)
        }
        verify(exactly = 0) { queryService.getHistory(any(), any(), any(), any()) }
    }
}
