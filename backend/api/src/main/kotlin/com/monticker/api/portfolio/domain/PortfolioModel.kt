package com.monticker.api.portfolio.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 계좌 데이터가 들어오는 길(ADR-106). MANUAL·CSV가 1단계, BROKER_API·MYDATA는 자문 뒤 2·3단계 */
enum class AccountSource { MANUAL, CSV, BROKER_API, MYDATA }

enum class AccountKind { GENERAL, ISA, PENSION, IRP, OTHER }

/** 증권사. 목록에 없는 곳은 OTHER — 이름은 계좌 이름에 적는다 */
enum class Institution { KIS, TOSS, KIWOOM, MIRAE, SAMSUNG, NH, KB, SHINHAN, HANA, DAISHIN, LS, MERITZ, KAKAOPAY, IBKR, OTHER }

enum class TransactionType {
    BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE, TAX;

    val isTrade get() = this == BUY || this == SELL
}

data class PortfolioAccount(
    val id: Long,
    val userId: Long,
    val source: AccountSource,
    val institution: Institution,
    val name: String,
    val accountKind: AccountKind,
    val accountRefMasked: String?,
    val createdAt: Instant,
)

/** 보유를 묶는 기준 — stocks에 있으면 stock_id, 없으면 상품 코드 */
data class InstrumentKey(val stockId: Long?, val code: String?) {
    init { require(stockId != null || code != null) { "종목 또는 상품 코드가 필요합니다" } }
}

data class PortfolioTransaction(
    val id: Long,
    val accountId: Long,
    val type: TransactionType,
    val tradeDate: LocalDate,
    val stockId: Long?,
    val instrumentCode: String?,
    val instrumentName: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    /** 수수료·세금을 뺀 거래 금액(거래 통화) */
    val amount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val currency: String,
    val memo: String?,
) {
    val instrumentKey: InstrumentKey?
        get() = if (stockId != null || instrumentCode != null) InstrumentKey(stockId, instrumentCode) else null
}
