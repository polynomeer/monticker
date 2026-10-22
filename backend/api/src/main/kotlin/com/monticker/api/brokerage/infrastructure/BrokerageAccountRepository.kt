package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageProvider
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface BrokerageAccountRepository : JpaRepository<BrokerageAccount, Long> {
    fun findByUserIdAndIsActiveTrue(userId: Long): Optional<BrokerageAccount>

    // ADR-109 — 탈퇴. 활성 계좌만이 아니라 키가 남은 비활성 계좌까지 본다.
    fun findAllByUserId(userId: Long): List<BrokerageAccount>

    // ADR-026 — 증권사/계좌번호 교체 시 예전에 쓰다 비활성화한 행을 재활성화하기 위한 조회.
    fun findByUserIdAndProviderAndAccountNumber(
        userId: Long,
        provider: BrokerageProvider,
        accountNumber: String,
    ): Optional<BrokerageAccount>
}
