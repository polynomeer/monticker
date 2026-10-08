package com.monticker.api.watchlist.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.watchlist.application.WatchlistService
import com.monticker.api.watchlist.domain.WatchlistGroup
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class WatchlistControllerTest {

    private val watchlistService = mockk<WatchlistService>()
    private val controller = WatchlistController(watchlistService)

    /** Stubs @AuthenticationPrincipal Long → fixed test user id (standalone MockMvc has no Spring Security filter chain). */
    private val authPrincipalResolver = object : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter) =
            parameter.parameterType == Long::class.java &&
                parameter.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal::class.java)

        override fun resolveArgument(
            parameter: MethodParameter, mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest, binderFactory: WebDataBinderFactory?,
        ): Any = 1L
    }

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(authPrincipalResolver)
        .setControllerAdvice(com.monticker.api.common.exception.GlobalExceptionHandler())
        .build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `GET watchlists returns groups`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "기술주")
        every { watchlistService.getGroups(any()) } returns listOf(group)
        every { watchlistService.get52WeekRanges(any()) } returns emptyMap()

        mockMvc.perform(get("/api/watchlists"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("기술주"))
    }

    @Test
    fun `POST groups creates group`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "기술주")
        every { watchlistService.createGroup(any(), eq("기술주")) } returns group

        mockMvc.perform(
            post("/api/watchlists/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"기술주"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("기술주"))
    }

    @Test
    fun `DELETE item returns 204`() {
        justRun { watchlistService.removeItem(any(), eq(1L)) }

        mockMvc.perform(delete("/api/watchlists/items/1"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `GET watchlists returns items in order with their 52 week range`() {
        val stock = com.monticker.api.stock.domain.Stock(id = 7L, symbol = "005930", name = "삼성전자", market = com.monticker.api.stock.domain.Market.KOSPI, exchange = "KRX")
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "기술주").apply {
            items += com.monticker.api.watchlist.domain.WatchlistItem(id = 3L, group = this, stock = stock, sortOrder = 0)
        }
        val range = com.monticker.api.marketdata.domain.PriceRange52w(
            7L, java.math.BigDecimal("90000"), java.math.BigDecimal("50000"),
            java.time.LocalDate.of(2025, 10, 9), java.time.LocalDate.of(2026, 3, 2), java.time.LocalDate.of(2026, 10, 8), 150,
        )
        every { watchlistService.getGroups(any()) } returns listOf(group)
        every { watchlistService.get52WeekRanges(any()) } returns mapOf(7L to range)

        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(authPrincipalResolver)
            .setMessageConverters(org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
            ))
            .build()
        mvc.perform(get("/api/watchlists"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].items[0].sortOrder").value(0))
            .andExpect(jsonPath("$[0].items[0].range52w.high").value(90000))
            .andExpect(jsonPath("$[0].items[0].range52w.firstDate").value("2026-03-02"))
            .andExpect(jsonPath("$[0].items[0].range52w.fullPeriod").value(false))
    }

    @Test
    fun `PATCH sort-order moves the item and returns its new place`() {
        every { watchlistService.moveItem(1L, 3L, 0) } returns 0

        mockMvc.perform(
            patch("/api/watchlists/items/3/sort-order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sortOrder":0}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.itemId").value(3))
            .andExpect(jsonPath("$.sortOrder").value(0))
    }

    @Test
    fun `PATCH sort-order rejects a missing or negative position`() {
        for (body in listOf("""{}""", """{"sortOrder":-1}""")) {
            mockMvc.perform(patch("/api/watchlists/items/3/sort-order").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        io.mockk.verify(exactly = 0) { watchlistService.moveItem(any(), any(), any()) }
    }

    @Test
    fun `PATCH sort-order of someone else's item is the same 404 as a missing one`() {
        every { watchlistService.moveItem(1L, 99L, 0) } throws NoSuchElementException("Watchlist item not found: 99")

        mockMvc.perform(
            patch("/api/watchlists/items/99/sort-order").contentType(MediaType.APPLICATION_JSON).content("""{"sortOrder":0}""")
        ).andExpect(status().isNotFound)
    }
}
