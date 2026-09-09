package com.monticker.api.community.application

import com.monticker.api.community.domain.CommentReport
import com.monticker.api.community.domain.StockComment
import com.monticker.api.community.infrastructure.CommentReportRepository
import com.monticker.api.community.infrastructure.StockCommentRepository
import com.monticker.api.event.application.EventTimelineService
import com.monticker.api.event.domain.EventType
import com.monticker.api.event.domain.StockEvent
import com.monticker.api.stock.application.StockService
import com.monticker.api.stock.domain.Market
import com.monticker.api.stock.domain.Stock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class StockCommentServiceTest {

    private val commentRepository = mockk<StockCommentRepository>()
    private val reportRepository = mockk<CommentReportRepository>()
    private val stockService = mockk<StockService>()
    private val eventTimelineService = mockk<EventTimelineService>()
    private val moderationService = mockk<CommentModerationService>()
    private val jdbc = mockk<JdbcTemplate>()

    private val service = StockCommentService(
        commentRepository, reportRepository, stockService, eventTimelineService, moderationService, jdbc,
    )

    private fun stock(id: Long) = Stock(id = id, symbol = "005930", name = "삼성전자", market = Market.KOSPI, exchange = "KRX")
    private fun event(id: Long, stockId: Long) = StockEvent(
        id = id, stockId = stockId, eventType = EventType.NEWS_PUBLISHED, title = "테스트 이벤트", eventTime = java.time.Instant.now(),
    )

    private fun mockNicknameLookup(vararg pairs: Pair<Long, String>) {
        every { jdbc.query(any<String>(), any<RowMapper<Pair<Long, String>>>(), *anyVararg()) } returns pairs.toList()
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    fun `정상 댓글은 모더레이션 통과 후 저장된다`() {
        every { stockService.getById(1L) } returns stock(1L)
        every { moderationService.check("좋은 실적이네요") } returns ModerationResult.Allowed
        val saved = StockComment(id = 10, stockId = 1L, userId = 5L, content = "좋은 실적이네요")
        every { commentRepository.save(any()) } returns saved
        mockNicknameLookup(5L to "닉네임")

        val result = service.create(5L, 1L, "좋은 실적이네요", null)

        assertThat(result.id).isEqualTo(10)
        assertThat(result.authorNickname).isEqualTo("닉네임")
    }

    @Test
    fun `모더레이션이 차단하면 저장하지 않고 예외를 던진다`() {
        every { stockService.getById(1L) } returns stock(1L)
        every { moderationService.check(any()) } returns ModerationResult.Blocked("매수·매도를 권유하는 표현은 게시할 수 없습니다.")

        assertThatThrownBy { service.create(5L, 1L, "지금 매수하세요", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("매수·매도를 권유")

        verify(exactly = 0) { commentRepository.save(any()) }
    }

    @Test
    fun `다른 종목의 이벤트를 태그하면 거부된다`() {
        every { stockService.getById(1L) } returns stock(1L)
        every { eventTimelineService.getById(99L) } returns event(99L, stockId = 2L)

        assertThatThrownBy { service.create(5L, 1L, "댓글", 99L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("이 종목의 이벤트가 아닙니다")
    }

    @Test
    fun `존재하지 않는 종목이면 예외가 전파된다`() {
        every { stockService.getById(999L) } throws NoSuchElementException("종목 없음")

        assertThatThrownBy { service.create(5L, 999L, "댓글", null) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `본인 댓글은 삭제할 수 있다`() {
        val comment = StockComment(id = 1, stockId = 1L, userId = 5L, content = "댓글")
        every { commentRepository.findById(1L) } returns java.util.Optional.of(comment)
        val slot = slot<StockComment>()
        every { commentRepository.save(capture(slot)) } answers { slot.captured }

        service.delete(5L, 1L)

        assertThat(slot.captured.deletedAt).isNotNull()
    }

    @Test
    fun `타인 댓글은 삭제할 수 없다`() {
        val comment = StockComment(id = 1, stockId = 1L, userId = 5L, content = "댓글")
        every { commentRepository.findById(1L) } returns java.util.Optional.of(comment)

        assertThatThrownBy { service.delete(99L, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("본인 댓글만")
    }

    // ── report ───────────────────────────────────────────────────────────────

    @Test
    fun `댓글을 신고할 수 있다`() {
        every { commentRepository.findById(1L) } returns java.util.Optional.of(StockComment(id = 1, stockId = 1L, userId = 5L, content = "댓글"))
        every { reportRepository.existsByCommentIdAndReporterId(1L, 99L) } returns false
        every { reportRepository.save(any()) } returns CommentReport(id = 1, commentId = 1L, reporterId = 99L, reason = "부적절함")

        service.report(99L, 1L, "부적절함")

        verify { reportRepository.save(any()) }
    }

    @Test
    fun `이미 신고한 댓글은 중복 신고할 수 없다`() {
        every { commentRepository.findById(1L) } returns java.util.Optional.of(StockComment(id = 1, stockId = 1L, userId = 5L, content = "댓글"))
        every { reportRepository.existsByCommentIdAndReporterId(1L, 99L) } returns true

        assertThatThrownBy { service.report(99L, 1L, "또 신고") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("이미 신고")
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    fun `eventId 없이 조회하면 종목 전체 댓글을 반환한다`() {
        val comments = listOf(StockComment(id = 1, stockId = 1L, userId = 5L, content = "댓글1"))
        every { commentRepository.findByStockIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L, any()) } returns comments
        mockNicknameLookup(5L to "닉네임")

        val result = service.list(1L, null, PageRequest.of(0, 20))

        assertThat(result).hasSize(1)
        assertThat(result[0].authorNickname).isEqualTo("닉네임")
    }
}
