package com.monticker.api.screener.api

import com.monticker.api.screener.application.SavedScreenService
import com.monticker.api.screener.application.ScreenerService
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 보안 리뷰 — 공개 스크리너의 offset이 무제한이면 깊은 OFFSET 스캔을 비로그인으로 반복시킬 수 있다. */
class ScreenerControllerOffsetTest {
    private val service = mockk<ScreenerService>(relaxed = true)
    private val controller = ScreenerController(service, mockk<SavedScreenService>())

    @Test
    fun `rejects an offset above the cap before querying`() {
        assertThatThrownBy {
            controller.getScreener("realtime", "all", "amount", 20, ScreenerController.MAX_OFFSET + 1, "all", null, null, null, null, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { service.getItems(any(), any(), any(), any(), any()) }
    }
}
