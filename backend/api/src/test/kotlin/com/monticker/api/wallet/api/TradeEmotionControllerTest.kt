package com.monticker.api.wallet.api

import com.monticker.api.common.exception.GlobalExceptionHandler
import com.monticker.api.paper.application.PaperTradeQueryService
import com.monticker.api.paper.application.PaperTradeSummary
import com.monticker.api.wallet.application.EmotionTagService
import com.monticker.api.wallet.application.ReceiptService
import com.monticker.api.wallet.domain.EmotionTag
import com.monticker.api.wallet.domain.EmotionType
import com.monticker.api.wallet.infrastructure.EmotionTagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

/**
 * GET/POST /api/paper/trades/{id}/emotion 의 소유권 검사(IDOR) — 실제 EmotionTagService와
 * GlobalExceptionHandler를 거쳐 HTTP 상태까지 확인한다.
 */
class TradeEmotionControllerTest {

    private val emotionTagRepo = mockk<EmotionTagRepository>()
    private val tradeQueryService = mockk<PaperTradeQueryService>()
    private val emotionTagService = EmotionTagService(emotionTagRepo, tradeQueryService, mockk<JdbcTemplate>())
    private val controller = TradeReceiptController(mockk<ReceiptService>(), emotionTagService)

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val owner = 1L
    private val attacker = 2L
    private val tradeId = 10L
    private val ownersTag = EmotionTag(
        id = 5L, paperTradeId = tradeId, userId = owner, emotion = EmotionType.ANXIOUS, memo = "개인 메모",
    )

    private fun loginAs(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
    }

    @BeforeEach
    fun setUp() {
        every { tradeQueryService.findById(tradeId) } returns PaperTradeSummary(
            id = tradeId, userId = owner, stockId = 100L, side = "BUY",
            quantity = 1, price = BigDecimal("100"), amount = BigDecimal("100"),
        )
        every { tradeQueryService.findById(404L) } returns null
        every { emotionTagRepo.findByPaperTradeId(tradeId) } returns ownersTag
        every { emotionTagRepo.delete(any()) } returns Unit
        every { emotionTagRepo.save(any()) } answers { firstArg() }
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `owner can read their own emotion tag`() {
        loginAs(owner)

        mockMvc.perform(get("/api/paper/trades/$tradeId/emotion"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emotion").value("ANXIOUS"))
            .andExpect(jsonPath("$.memo").value("개인 메모"))
    }

    @Test
    fun `owner can overwrite their own emotion tag`() {
        loginAs(owner)

        mockMvc.perform(
            post("/api/paper/trades/$tradeId/emotion")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"emotion":"LONG_TERM","memo":"다시 생각해보니"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emotion").value("LONG_TERM"))
            .andExpect(jsonPath("$.userId").value(owner))

        verify { emotionTagRepo.delete(ownersTag) }
    }

    @Test
    fun `another user gets 404 reading the owner's emotion tag`() {
        loginAs(attacker)

        mockMvc.perform(get("/api/paper/trades/$tradeId/emotion"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.memo").doesNotExist())
    }

    @Test
    fun `another user gets 404 and cannot overwrite the owner's emotion tag`() {
        loginAs(attacker)

        mockMvc.perform(
            post("/api/paper/trades/$tradeId/emotion")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"emotion":"FOMO","memo":"덮어쓰기"}""")
        )
            .andExpect(status().isNotFound)

        verify(exactly = 0) { emotionTagRepo.delete(any()) }
        verify(exactly = 0) { emotionTagRepo.save(any()) }
    }

    @Test
    fun `another user's trade is indistinguishable from a missing trade`() {
        loginAs(attacker)

        val notOwned = mockMvc.perform(get("/api/paper/trades/$tradeId/emotion"))
            .andExpect(status().isNotFound).andReturn().response.contentAsString
        val missing = mockMvc.perform(get("/api/paper/trades/404/emotion"))
            .andExpect(status().isNotFound).andReturn().response.contentAsString

        // timestamp와 요청한 id만 다르고 나머지(상태·메시지 형태)는 같아야 한다
        fun normalize(body: String) = body
            .replace(Regex("\"timestamp\":[^,}]*"), "")
            .replace("trade not found: $tradeId", "trade not found: ID")
            .replace("trade not found: 404", "trade not found: ID")
        assertThat(normalize(notOwned)).isEqualTo(normalize(missing))
    }
}
