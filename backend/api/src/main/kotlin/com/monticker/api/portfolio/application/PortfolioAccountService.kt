package com.monticker.api.portfolio.application

import com.monticker.api.common.consent.ConsentGroup
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentSource
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.PortfolioAccount
import com.monticker.api.portfolio.domain.PortfolioTransaction
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 통합 포트폴리오의 계좌·거래 관리(ADR-106 1단계 — 손 입력·파일 업로드). 이용자가 넣은 기록이라 지우면 남기지 않는다.
 *
 * 계좌를 만들려면 "계좌 정보 수집·이용" 동의(ConsentGroup.PORTFOLIO_DATA)가 현재 버전으로 있어야 한다. 처음이면 요청에 담아
 * 보내고 여기서 기록한다. 데이터 전체 삭제([deleteAll])는 그 동의의 철회이기도 하다.
 */
@Service
class PortfolioAccountService(
    private val accounts: PortfolioAccountRepository,
    private val transactions: PortfolioTransactionRepository,
    private val normalizer: TransactionNormalizer,
    private val consents: ConsentService,
) {
    fun list(userId: Long): List<PortfolioAccount> = accounts.findAll(userId)

    /** 손 입력(MANUAL) 또는 파일 업로드(CSV) 계좌. 증권사 API·마이데이터 계좌는 2·3단계(자문 뒤)라 여기서 만들지 않는다 */
    @Transactional
    fun create(
        userId: Long, source: AccountSource, name: String, institution: Institution, kind: AccountKind, refLast4: String?,
        givenConsents: Collection<String> = emptyList(),
    ): PortfolioAccount {
        require(source == AccountSource.MANUAL || source == AccountSource.CSV) { "손 입력·파일 업로드 계좌만 만들 수 있습니다" }
        val trimmed = validName(name)
        val masked = refLast4?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.matches(Regex("\\d{4}"))) { "계좌번호는 끝 4자리 숫자만 넣습니다" }
            "****$it"
        }
        if (accounts.count(userId) >= MAX_ACCOUNTS) throw BusinessRuleException("계좌는 ${MAX_ACCOUNTS}개까지 만들 수 있습니다")
        // 이미 현재 버전에 동의했으면 비어 있어도 통과한다. 처음이거나 문서가 바뀌었으면 요청에 담겨 있어야 한다
        consents.requireMissingAndRecord(userId, ConsentGroup.PORTFOLIO_DATA, givenConsents, ConsentSource.PORTFOLIO)
        val id = accounts.insert(userId, source, institution, trimmed, kind, masked)
        return accounts.find(userId, id)!!
    }

    @Transactional
    fun update(userId: Long, accountId: Long, name: String, kind: AccountKind): PortfolioAccount {
        if (!accounts.update(userId, accountId, validName(name), kind)) throw NoSuchElementException("계좌를 찾을 수 없습니다")
        return accounts.find(userId, accountId)!!
    }

    @Transactional
    fun delete(userId: Long, accountId: Long) {
        if (!accounts.delete(userId, accountId)) throw NoSuchElementException("계좌를 찾을 수 없습니다")
    }

    /** 통합 포트폴리오 데이터를 모두 지우고 동의를 철회한다(되돌릴 수 없다). 모의계좌·증권사 주문 연동은 건드리지 않는다 */
    @Transactional
    fun deleteAll(userId: Long): Int {
        val deleted = accounts.deleteAll(userId)
        consents.withdrawFeature(userId, ConsentGroup.PORTFOLIO_DATA, ConsentSource.PORTFOLIO)
        return deleted
    }

    fun transactions(userId: Long, accountId: Long, page: Int, size: Int): List<PortfolioTransaction> {
        requireAccount(userId, accountId)
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200입니다" }
        return transactions.findPage(userId, accountId, size, page * size)
    }

    @Transactional
    fun addManual(userId: Long, accountId: Long, input: TransactionInput): Long {
        val account = requireAccount(userId, accountId)
        require(account.source == AccountSource.MANUAL) { "손 입력 계좌에만 거래를 직접 넣을 수 있습니다" }
        if (transactions.count(accountId) >= MAX_TRANSACTIONS_PER_ACCOUNT) {
            throw BusinessRuleException("계좌당 거래는 ${MAX_TRANSACTIONS_PER_ACCOUNT}건까지입니다")
        }
        // 손 입력은 같은 날 같은 거래를 두 번 넣을 수 있다(실제로 그런 매매가 있다) — 지문은 매번 새로 만든다
        return transactions.insert(accountId, normalizer.normalize(input).copy(fingerprint = "manual:" + UUID.randomUUID()))
    }

    @Transactional
    fun deleteTransaction(userId: Long, accountId: Long, transactionId: Long) {
        if (!transactions.delete(userId, accountId, transactionId)) throw NoSuchElementException("거래를 찾을 수 없습니다")
    }

    private fun requireAccount(userId: Long, accountId: Long): PortfolioAccount =
        accounts.find(userId, accountId) ?: throw NoSuchElementException("계좌를 찾을 수 없습니다")

    private fun validName(name: String): String = name.trim().also { require(it.length in 1..50) { "계좌 이름은 1~50자입니다" } }

    companion object {
        const val MAX_ACCOUNTS = 20
        const val MAX_TRANSACTIONS_PER_ACCOUNT = 50_000
    }
}
