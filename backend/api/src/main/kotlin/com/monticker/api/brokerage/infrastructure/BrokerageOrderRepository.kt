package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface BrokerageOrderRepository : JpaRepository<BrokerageOrder, Long> {
    fun findAllByUserIdOrderBySubmittedAtDesc(userId: Long, pageable: Pageable): Page<BrokerageOrder>
    fun findAllByAccountIdAndStatus(accountId: Long, status: BrokerageOrderStatus): List<BrokerageOrder>
    fun findByPgOrderId(pgOrderId: String): BrokerageOrder?
    // 차트 위 주문선용 — 종목 상세 화면 하나에 대한 미체결 주문만 소량 조회하므로
    // 페이지네이션 없는 단순 리스트로 충분하다.
    fun findAllByUserIdAndSymbolAndStatusIn(userId: Long, symbol: String, statuses: List<BrokerageOrderStatus>): List<BrokerageOrder>
}
