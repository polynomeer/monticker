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
        .build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `GET watchlists returns groups`() {
        val group = WatchlistGroup(id = 1L, userId = 1L, name = "기술주")
        every { watchlistService.getGroups(any()) } returns listOf(group)

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
}
