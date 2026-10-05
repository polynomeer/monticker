package com.monticker.api.batch

import com.monticker.api.batch.brokerage.BrokerageSettlementJobConfig
import com.monticker.api.batch.settlement.PaperSettlementJobConfig
import com.monticker.api.brokerage.infrastructure.BrokerageSettlementRepository
import com.monticker.api.paper.infrastructure.PaperSettlementRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.batch.item.ExecutionContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * 정산 리더는 실행마다 잡 파라미터의 date로 기준일을 정해야 한다. 예전엔 싱글턴 빈이 LocalDate.now()를
 * 기동 시점에 굳혀, JVM이 며칠 떠 있으면 그 뒤에 도래한 정산(모의·실거래 모두)을 재기동 전까지 읽지 않았다.
 */
class SettlementReaderDateTest {

    private val date = LocalDate.of(2026, 10, 7)

    @Test
    fun `모의 정산 리더는 잡 파라미터의 날짜로 기준일 쿼리를 한다`() {
        val repo = mockk<PaperSettlementRepository>()
        every { repo.findDueSettlementsAfter(any(), any(), any()) } returns emptyList()
        val config = PaperSettlementJobConfig(mockk(), mockk(), repo, mockk())

        config.dueSettlementReader(date.toString()).apply { open(ExecutionContext()); read() }

        verify { repo.findDueSettlementsAfter(date, 0L, any()) }
    }

    @Test
    fun `실거래 정산 리더는 잡 파라미터의 날짜로 기준일 쿼리를 한다`() {
        val repo = mockk<BrokerageSettlementRepository>()
        every { repo.findDueSettlementsAfter(any(), any(), any()) } returns emptyList()
        val config = BrokerageSettlementJobConfig(mockk(), mockk(), repo, mockk())

        config.dueBrokerageSettlementReader(date.toString()).apply { open(ExecutionContext()); read() }

        verify { repo.findDueSettlementsAfter(date, 0L, any()) }
    }

    @Test
    fun `날짜 파라미터가 없으면 KST 오늘을 쓴다`() {
        val repo = mockk<PaperSettlementRepository>()
        every { repo.findDueSettlementsAfter(any(), any(), any()) } returns emptyList()
        val config = PaperSettlementJobConfig(mockk(), mockk(), repo, mockk())

        config.dueSettlementReader(null).apply { open(ExecutionContext()); read() }

        verify { repo.findDueSettlementsAfter(LocalDate.now(ZoneId.of("Asia/Seoul")), 0L, any()) }
    }
}
