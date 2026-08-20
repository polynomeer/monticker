package com.monticker.api.matching.api

import com.monticker.api.matching.application.MatchingService
import com.monticker.api.matching.application.OrderDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/**
 * MatchingController의 userId() 헬퍼 — X-User-Id 헤더 추출 계약 검증.
 *
 * JWT 없이 X-User-Id 헤더로 userId를 전달하는 내부 서비스 패턴(API 게이트웨이가
 * JWT 검증 후 헤더를 세팅해 전달).
 *
 * 이전 버전은 이 계약을 컨트롤러 밖에 복제한 private 함수로 검증해 실제
 * MatchingController를 전혀 호출하지 않았다 — 실제 컨트롤러가 리팩터링/삭제돼도
 * 테스트는 계속 통과했을 것이다. MockMvc standalone으로 실제 컨트롤러를 호출해
 * 검증하도록 다시 작성했다 (MatchingService는 실제 시스템 경계인 DB/상태기계를
 * 감싸는 컬렉션이므로 여기서는 모킹).
 */
class MatchingControllerUserIdTest {

    private val matchingService: MatchingService = mockk()
    private val mockMvc: MockMvc =
        MockMvcBuilders.standaloneSetup(MatchingController(matchingService)).build()

    private fun activeOrder() = OrderDto(
        id = 1L, stockId = 100L, side = "BUY", orderType = "MARKET",
        quantity = 1, limitPrice = null, filledQty = 0, avgFillPrice = null,
        status = "PENDING", rejectReason = null, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `X-User-Id 헤더가 있으면 실제 컨트롤러가 userId를 추출해 서비스에 위임한다`() {
        every { matchingService.getActiveOrders(42L) } returns listOf(activeOrder())

        mockMvc.perform(get("/api/matching/orders").header("X-User-Id", "42"))
            .andExpect(status().isOk)

        verify { matchingService.getActiveOrders(42L) }
    }

    @Test
    fun `X-User-Id 헤더가 없으면 401을 반환한다`() {
        mockMvc.perform(get("/api/matching/orders"))
            .andExpect(status().isUnauthorized)

        verify(exactly = 0) { matchingService.getActiveOrders(any()) }
    }

    @Test
    fun `X-User-Id 헤더가 숫자가 아니면 400 잘못된 요청으로 처리된다`() {
        // submitOrder 경로는 IllegalArgumentException(NumberFormatException의 상위 타입)을
        // 잡아 400으로 변환한다 — userId() 추출이 요청 처리 안에서 일어나기 때문에 이 catch에 걸린다.
        val body = """{"stockId":100,"side":"BUY","orderType":"MARKET","quantity":1}"""

        mockMvc.perform(
            post("/api/matching/orders")
                .header("X-User-Id", "not-a-number")
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { matchingService.submitOrderChecked(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `다양한 userId 값을 실제 컨트롤러가 올바르게 파싱해 전달한다`() {
        listOf(1L, 99999L, Long.MAX_VALUE / 2).forEach { expected ->
            every { matchingService.getActiveOrders(expected) } returns emptyList()

            mockMvc.perform(get("/api/matching/orders").header("X-User-Id", expected.toString()))
                .andExpect(status().isOk)

            verify { matchingService.getActiveOrders(expected) }
        }
    }

    @Test
    fun `POST 주문 제출 시 헤더의 userId가 서비스 호출로 정확히 전달된다`() {
        val userIdSlot = slot<Long>()
        every {
            matchingService.submitOrderChecked(capture(userIdSlot), any(), any(), any(), any(), any())
        } returns com.monticker.api.matching.application.SubmitOrderResponse(
            order = activeOrder(), fills = emptyList(), message = "주문 접수 완료 (미체결)",
        )
        val body = """{"stockId":100,"side":"BUY","orderType":"MARKET","quantity":1}"""

        mockMvc.perform(
            post("/api/matching/orders")
                .header("X-User-Id", "7")
                .contentType("application/json")
                .content(body)
        ).andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals(7L, userIdSlot.captured)
    }
}
