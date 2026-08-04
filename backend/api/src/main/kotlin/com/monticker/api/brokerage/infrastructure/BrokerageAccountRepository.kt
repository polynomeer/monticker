package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageAccount
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface BrokerageAccountRepository : JpaRepository<BrokerageAccount, Long> {
    fun findByUserIdAndIsActiveTrue(userId: Long): Optional<BrokerageAccount>
}
