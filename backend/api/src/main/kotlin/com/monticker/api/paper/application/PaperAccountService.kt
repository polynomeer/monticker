package com.monticker.api.paper.application

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.paper.domain.PaperInitialCapital
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class PaperAccountResponse(
    val initialCapital: BigDecimal,
    val cash: BigDecimal,
    /** 이번 요청으로 계좌가 만들어졌으면 true, 이미 같은 시작 자금의 계좌가 있었으면 false(멱등). */
    val created: Boolean,
)

/**
 * ADR-089 — 온보딩에서 모의 계좌를 시작 자금과 함께 **처음 한 번** 만든다.
 *
 * - 시작 자금은 [PaperInitialCapital] 화이트리스트만 받는다(그 밖은 400).
 * - 이미 계좌가 있으면 잔고를 건드리지 않는다. 같은 시작 자금이면 그대로 돌려주고(재시도·뒤로 가기에 멱등),
 *   다르면 409 — 이 경로로 기존 계좌의 잔고를 바꿀 수 없다. 잔고 되돌리기는 기존 `/api/paper/reset`
 *   (미체결 주문 확인·조건부 주문 취소·원장 기록·하루 3회 제한)만 한다.
 * - 동시성: 첫 주문의 지연 생성(OrderSagaOrchestrator `ON CONFLICT DO NOTHING`)과 겹쳐도 같은 방식이라
 *   먼저 들어간 행이 이긴다. 진 쪽은 기존 행을 읽어 위 규칙대로 응답한다.
 */
@Service
class PaperAccountService(private val jdbc: JdbcTemplate) {

    @Transactional
    fun open(userId: Long, requested: BigDecimal?): PaperAccountResponse {
        val capital = PaperInitialCapital.parse(requested).amount
        val inserted = jdbc.update(
            """
            INSERT INTO paper_accounts (user_id, cash, initial_capital, created_at, updated_at)
            VALUES (?, ?, ?, now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """.trimIndent(),
            userId, capital, capital,
        )
        val current = find(userId) ?: error("paper_accounts row missing after insert: userId=$userId")
        if (inserted == 1) return current.copy(created = true)
        if (current.initialCapital.compareTo(capital) != 0) {
            throw BusinessRuleException(
                "이미 시작 자금 ${"%,d".format(current.initialCapital.toBigInteger())}원으로 만든 모의 계좌가 있습니다. " +
                    "시작 자금은 계좌를 처음 만들 때만 정할 수 있습니다.",
            )
        }
        return current
    }

    @Transactional(readOnly = true)
    fun find(userId: Long): PaperAccountResponse? =
        jdbc.query(
            "SELECT initial_capital, cash FROM paper_accounts WHERE user_id = ?",
            { rs, _ -> PaperAccountResponse(rs.getBigDecimal("initial_capital"), rs.getBigDecimal("cash"), created = false) },
            userId,
        ).firstOrNull()
}
