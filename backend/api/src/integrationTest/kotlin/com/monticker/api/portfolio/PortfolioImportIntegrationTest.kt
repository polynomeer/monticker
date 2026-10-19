package com.monticker.api.portfolio

import com.monticker.api.common.consent.ConsentService
import com.monticker.api.common.consent.ConsentType
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.portfolio.application.HoldingsScope
import com.monticker.api.portfolio.application.ImportOptions
import com.monticker.api.portfolio.application.ImportRowStatus
import com.monticker.api.portfolio.application.PortfolioAccountService
import com.monticker.api.portfolio.application.PortfolioHoldingsQueryService
import com.monticker.api.portfolio.application.PortfolioImportService
import com.monticker.api.portfolio.application.TransactionNormalizer
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.domain.csv.CsvField
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioImportBatchRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * CSV 가져오기(ADR-106 1단계)를 실제 Postgres(V104·V105)로 본다 — 미리보기 집계, 확정, 같은 파일·겹치는 파일 재업로드,
 * 같은 날 같은 거래 두 건, 오류 행 정책, 해시 확인, 되돌리기, 동의·계좌 종류·소유 검사.
 */
class PortfolioImportIntegrationTest : PostgresIntegrationTest() {

    private var consentMissing: Set<ConsentType> = emptySet()
    private val consents = mockk<ConsentService> {
        every { requireMissingAndRecord(any(), any(), any(), any()) } returns emptySet()
        every { missingRequired(any(), any()) } answers { consentMissing }
    }
    private val accounts by lazy { PortfolioAccountRepository(jdbcTemplate) }
    private val txs by lazy { PortfolioTransactionRepository(jdbcTemplate) }
    private val normalizer by lazy {
        TransactionNormalizer(jdbcTemplate).apply { clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Seoul")) }
    }
    private val accountService by lazy { PortfolioAccountService(accounts, txs, normalizer, consents) }
    private val imports by lazy { PortfolioImportService(accounts, txs, PortfolioImportBatchRepository(jdbcTemplate), normalizer, consents, jdbcTemplate) }
    private val holdings by lazy { PortfolioHoldingsQueryService(accounts, txs, mockk<PaperPortfolioQueryService>(), jdbcTemplate) }

    private var me = 0L
    private var other = 0L
    private var acc = 0L
    private lateinit var kr: String
    private lateinit var us: String

    private fun user() = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java, "i-${UUID.randomUUID()}@test.local", "i",
    )!!

    @BeforeEach
    fun setUp() {
        consentMissing = emptySet()
        me = user(); other = user()
        // 테스트끼리 종목 코드가 겹치지 않게 6자리 숫자·티커를 새로 만든다
        kr = (100000 + (Math.random() * 899999).toInt()).toString()
        us = "Z" + UUID.randomUUID().toString().filter { it.isLetter() }.take(5).uppercase()
        jdbcTemplate.update("INSERT INTO stocks (symbol, name, market, exchange, currency) VALUES (?, '국내종목', 'KOSPI', 'KRX', 'KRW')", kr)
        jdbcTemplate.update("INSERT INTO stocks (symbol, name, market, exchange, currency) VALUES (?, 'US Stock', 'NASDAQ', 'NASDAQ', 'USD')", us)
        acc = accountService.create(me, AccountSource.CSV, "키움 CSV", Institution.KIWOOM, AccountKind.GENERAL, null).id
    }

    private fun csv(vararg rows: String) = ("date,type,symbol,name,quantity,price,amount,fee,tax,currency\r\n" + rows.joinToString("\r\n") + "\r\n").toByteArray()

    @Test
    fun `미리보기는 저장하지 않고 새 행·오류·미매칭을 센다`() {
        val file = csv(
            "2026-10-01,BUY,$kr,,10,\"70,000\",,100,,",
            "2026-10-02,BUY,$us,,0.5,280.10,,,,",            // 통화가 비면 종목 통화(USD)
            "2026-10-03,BUY,KR5223941K16,채권형펀드,1000,1.02,,,,",
            "2026-10-04,환전,$kr,,1,1,,,,",
            "2026-10-05,BUY,$us,,1,1,,,,KRW",                // 종목 통화와 다름
        )
        val p = imports.preview(me, acc, file, ImportOptions())
        assertThat(p.rowCount).isEqualTo(5)
        assertThat(p.newCount).isEqualTo(3)
        assertThat(p.errorCount).isEqualTo(2)
        assertThat(p.unmatchedCount).isEqualTo(1)
        assertThat(p.rows.first { it.line == 3 }.currency).isEqualTo("USD")
        assertThat(p.rows.first { it.line == 5 }.message).contains("환전")
        assertThat(p.rows.first { it.line == 6 }.message).contains("USD")
        assertThat(txs.count(acc)).isZero()
    }

    @Test
    fun `확정하면 보유에 들어가고 같은 파일을 다시 올리면 전부 중복이다`() {
        val file = csv("2026-10-01,BUY,$kr,,10,70000,,100,,", "2026-10-10,SELL,$kr,,4,78000,,100,546,")
        val first = imports.commit(me, acc, file, ImportOptions(), skipErrors = false, expectedSha256 = null)
        assertThat(first.imported).isEqualTo(2)
        val h = holdings.holdings(me, HoldingsScope.REAL).holdings.single()
        assertThat(h.quantity).isEqualByComparingTo("6")
        assertThat(h.realizedPnl).isEqualByComparingTo("31314")          // 312,000 - 646 - 4 × 70,010(평균 원가, 수수료 포함)

        val again = imports.preview(me, acc, file, ImportOptions())
        assertThat(again.newCount).isZero()
        assertThat(again.duplicateCount).isEqualTo(2)
        val second = imports.commit(me, acc, file, ImportOptions(), skipErrors = false, expectedSha256 = again.fileSha256)
        assertThat(second.imported).isZero()
        assertThat(second.duplicates).isEqualTo(2)
        assertThat(txs.count(acc)).isEqualTo(2)
    }

    @Test
    fun `기간이 겹치는 파일은 겹친 부분만 건너뛴다 — 같은 날 똑같은 거래 두 건은 둘 다 남는다`() {
        val jan = csv("2026-01-05,BUY,$kr,,1,100,,,,", "2026-01-05,BUY,$kr,,1,100,,,,", "2026-02-01,BUY,$kr,,2,110,,,,")
        assertThat(imports.commit(me, acc, jan, ImportOptions(), false, null).imported).isEqualTo(3)
        val janToMar = csv("2026-01-05,BUY,$kr,,1,100,,,,", "2026-01-05,BUY,$kr,,1,100,,,,", "2026-02-01,BUY,$kr,,2,110,,,,", "2026-03-01,SELL,$kr,,1,120,,,,")
        val r = imports.commit(me, acc, janToMar, ImportOptions(), false, null)
        assertThat(r.imported).isEqualTo(1)
        assertThat(r.duplicates).isEqualTo(3)
        assertThat(holdings.holdings(me, HoldingsScope.REAL).holdings.single().quantity).isEqualByComparingTo("3")
    }

    @Test
    fun `오류 행이 있으면 거부하고 건너뛰기를 고르면 그 행만 빼고 넣는다`() {
        val file = csv("2026-10-01,BUY,$kr,,1,100,,,,", "2026-10-02,BUY,$kr,,x,100,,,,")
        assertThatThrownBy { imports.commit(me, acc, file, ImportOptions(), false, null) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("오류 행이 1개")
        assertThat(txs.count(acc)).isZero()
        val r = imports.commit(me, acc, file, ImportOptions(), skipErrors = true, expectedSha256 = null)
        assertThat(r.imported).isEqualTo(1)
        assertThat(r.skipped).isEqualTo(1)
        assertThat(imports.list(me, acc).single().skipped).isEqualTo(1)
    }

    @Test
    fun `미리보기한 파일과 다르면 확정하지 않는다`() {
        val previewed = imports.preview(me, acc, csv("2026-10-01,BUY,$kr,,1,100,,,,"), ImportOptions())
        assertThatThrownBy { imports.commit(me, acc, csv("2026-10-01,BUY,$kr,,2,100,,,,"), ImportOptions(), false, previewed.fileSha256) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("미리보기한 파일")
    }

    @Test
    fun `가져오기 한 번을 되돌리면 그때 들어온 거래만 지운다`() {
        val manualFirst = imports.commit(me, acc, csv("2026-10-01,BUY,$kr,,1,100,,,,"), ImportOptions(), false, null)
        val second = imports.commit(me, acc, csv("2026-10-02,BUY,$kr,,5,100,,,,"), ImportOptions(), false, null)
        imports.revert(me, acc, second.batchId)
        assertThat(txs.count(acc)).isEqualTo(1)
        assertThat(imports.list(me, acc).map { it.id }).containsExactly(manualFirst.batchId)
        assertThatThrownBy { imports.revert(other, acc, manualFirst.batchId) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `CP949 열 매핑 파일을 가져온다`() {
        val text = "체결일,매매구분,종목번호,체결수량,체결단가\r\n2026.10.01,매수,A$kr,3,\"180,000\"\r\n"
        val opts = ImportOptions(
            format = "CUSTOM",
            columns = mapOf(CsvField.DATE to "체결일", CsvField.TYPE to "매매구분", CsvField.SYMBOL to "종목번호",
                CsvField.QUANTITY to "체결수량", CsvField.PRICE to "체결단가"),
        )
        val p = imports.preview(me, acc, text.toByteArray(Charset.forName("MS949")), opts)
        assertThat(p.charset).isEqualTo("CP949")
        assertThat(p.newCount).isEqualTo(1)
        assertThat(p.rows.single().type).isEqualTo(TransactionType.BUY)
        assertThat(p.rows.single().stockId).isNotNull()
    }

    @Test
    fun `동의가 없거나 손 입력 계좌이거나 남의 계좌면 가져오지 않는다`() {
        val file = csv("2026-10-01,BUY,$kr,,1,100,,,,")
        consentMissing = setOf(ConsentType.PORTFOLIO_DATA_COLLECTION)
        assertThatThrownBy { imports.preview(me, acc, file, ImportOptions()) }.hasMessageContaining("동의")
        consentMissing = emptySet()

        val manual = accountService.create(me, AccountSource.MANUAL, "손 입력", Institution.KIS, AccountKind.GENERAL, null).id
        assertThatThrownBy { imports.preview(me, manual, file, ImportOptions()) }.hasMessageContaining("파일 업로드 계좌")
        assertThatThrownBy { imports.preview(other, acc, file, ImportOptions()) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { accountService.addManual(me, acc, com.monticker.api.portfolio.application.TransactionInput(TransactionType.DEPOSIT, java.time.LocalDate.parse("2026-10-01"), amount = java.math.BigDecimal.ONE)) }
            .hasMessageContaining("손 입력 계좌")
    }

    @Test
    fun `데이터 전체 삭제는 계좌·거래·가져오기 기록을 지운다`() {
        imports.commit(me, acc, csv("2026-10-01,BUY,$kr,,1,100,,,,"), ImportOptions(), false, null)
        every { consents.withdrawFeature(any(), any(), any()) } returns Unit
        assertThat(accountService.deleteAll(me)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM portfolio_import_batches WHERE account_id = ?", Int::class.java, acc)).isZero()
        assertThat(txs.count(acc)).isZero()
    }
}
