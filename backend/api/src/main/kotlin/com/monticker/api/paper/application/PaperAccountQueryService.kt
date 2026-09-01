package com.monticker.api.paper.application

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * paper_accounts에 대한 읽기 전용 조회 — wallet 모듈(WalletService)이
 * paper.infrastructure.PaperAccountRepository를 직접 참조하지 않도록 감싼다.
 */
@Service
@Transactional(readOnly = true)
class PaperAccountQueryService(
    private val accountRepo: PaperAccountRepository,
) {
    fun getCashBalance(userId: Long): Money =
        accountRepo.findByUserId(userId).map { it.cash }.orElse(Money.INITIAL_BALANCE)
}
