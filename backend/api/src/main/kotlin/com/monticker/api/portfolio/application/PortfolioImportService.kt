package com.monticker.api.portfolio.application

import com.monticker.api.common.consent.ConsentGroup
import com.monticker.api.common.consent.ConsentService
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.ImportBatch
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.domain.csv.CsvField
import com.monticker.api.portfolio.domain.csv.CsvFormat
import com.monticker.api.portfolio.domain.csv.CsvRow
import com.monticker.api.portfolio.domain.csv.TransactionCsvParser
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioImportBatchRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository.NewTransaction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/** 이용자가 고른 형식. CUSTOM이면 필드 → 헤더 이름과(선택) 거래 구분 값 → 종류를 직접 준다 */
data class ImportOptions(
    val format: String = CsvFormat.MONTICKER_V1.id,
    val columns: Map<CsvField, String>? = null,
    val typeValues: Map<String, TransactionType>? = null,
    val defaultCurrency: String? = null,
)

enum class ImportRowStatus { NEW, DUPLICATE, ERROR }

data class ImportRowResult(
    val line: Int,
    val status: ImportRowStatus,
    val type: TransactionType?,
    val tradeDate: LocalDate?,
    val symbol: String?,
    val name: String?,
    /** stocks와 맞춘 종목. null이면 코드 그대로 들어간다(시세 없음) */
    val stockId: Long?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    val amount: BigDecimal?,
    val fee: BigDecimal?,
    val tax: BigDecimal?,
    val currency: String?,
    val message: String?,
)

data class ImportPreview(
    val fileSha256: String,
    val format: String,
    val charset: String,
    val rowCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val errorCount: Int,
    /** stocks에서 찾지 못해 코드 그대로 들어갈 행(시세가 없다) */
    val unmatchedCount: Int,
    /** 오류·미매칭 행을 먼저, 최대 [PortfolioImportService.PREVIEW_ROWS]행 */
    val rows: List<ImportRowResult>,
)

data class ImportResult(val batchId: Long, val imported: Int, val duplicates: Int, val skipped: Int)

/**
 * 거래내역 CSV 가져오기(ADR-106 1단계). 미리보기와 확정은 **같은 파일을 두 번 받는다** — 서버에 미리보기 상태를 저장하지 않는다
 * (원본·중간 결과를 남기지 않는다). 확정할 때 다시 계산하고, 이용자가 본 파일과 같은지 해시로 확인한다.
 *
 * - 행 검증은 손 입력과 같은 규칙이다([TransactionNormalizer]).
 * - 오류 행이 하나라도 있으면 기본으로 **전부 거부한다** — 행이 조용히 빠지면 보유가 틀어진다. 이용자가 skipErrors를 고르면
 *   오류 행만 빼고 넣고, 뺀 수를 기록한다.
 * - 지문 = 행 내용 + 파일 안에서 같은 내용이 몇 번째인지. 같은 파일·기간이 겹치는 파일을 다시 올려도 중복되지 않고,
 *   같은 날 똑같은 매매 두 건은 둘 다 들어간다.
 */
@Service
class PortfolioImportService(
    private val accounts: PortfolioAccountRepository,
    private val transactions: PortfolioTransactionRepository,
    private val batches: PortfolioImportBatchRepository,
    private val normalizer: TransactionNormalizer,
    private val consents: ConsentService,
    private val jdbc: JdbcTemplate,
) {
    private data class Evaluated(val line: Int, val result: ImportRowResult, val tx: NewTransaction?)

    @Transactional(readOnly = true)
    fun preview(userId: Long, accountId: Long, file: ByteArray, options: ImportOptions): ImportPreview {
        val (evaluated, charset, format) = evaluate(userId, accountId, file, options)
        val results = evaluated.map { it.result }
        val shown = results.sortedBy { if (it.status == ImportRowStatus.ERROR) 0 else if (it.stockId == null && it.type?.isTrade == true) 1 else 2 }
            .take(PREVIEW_ROWS).sortedBy { it.line }
        return ImportPreview(
            fileSha256 = sha256(file), format = format.id, charset = charset, rowCount = results.size,
            newCount = results.count { it.status == ImportRowStatus.NEW },
            duplicateCount = results.count { it.status == ImportRowStatus.DUPLICATE },
            errorCount = results.count { it.status == ImportRowStatus.ERROR },
            unmatchedCount = results.count { it.status != ImportRowStatus.ERROR && it.stockId == null && (it.type?.isTrade == true || it.type == TransactionType.DIVIDEND) },
            rows = shown,
        )
    }

    @Transactional
    fun commit(userId: Long, accountId: Long, file: ByteArray, options: ImportOptions, skipErrors: Boolean, expectedSha256: String?): ImportResult {
        val hash = sha256(file)
        require(expectedSha256 == null || expectedSha256.equals(hash, ignoreCase = true)) { "미리보기한 파일과 다릅니다. 다시 미리보기 해 주세요" }
        val (evaluated, _, format) = evaluate(userId, accountId, file, options)
        val errors = evaluated.count { it.result.status == ImportRowStatus.ERROR }
        require(errors == 0 || skipErrors) { "오류 행이 ${errors}개 있습니다. 고치거나 오류 행 건너뛰기를 선택하세요" }
        val toInsert = evaluated.mapNotNull { e -> e.tx.takeIf { e.result.status == ImportRowStatus.NEW } }
        require(transactions.count(accountId) + toInsert.size <= PortfolioAccountService.MAX_TRANSACTIONS_PER_ACCOUNT) {
            "계좌당 거래는 ${PortfolioAccountService.MAX_TRANSACTIONS_PER_ACCOUNT}건까지입니다"
        }

        val batchId = batches.insert(accountId, format.id, hash, evaluated.size)
        // 동시에 같은 파일을 두 번 확정해도 지문 UNIQUE가 막는다 — 실제로 들어간 수만 센다
        val imported = toInsert.count { transactions.insertIfAbsent(accountId, it, batchId) }
        val duplicates = evaluated.count { it.result.status == ImportRowStatus.DUPLICATE } + (toInsert.size - imported)
        batches.finish(batchId, imported, duplicates, errors)
        return ImportResult(batchId, imported, duplicates, errors)
    }

    fun list(userId: Long, accountId: Long): List<ImportBatch> {
        accounts.find(userId, accountId) ?: throw NoSuchElementException("계좌를 찾을 수 없습니다")
        return batches.findByAccount(accountId)
    }

    /** 가져오기 한 번을 되돌린다 — 그때 들어온 거래만 지운다 */
    @Transactional
    fun revert(userId: Long, accountId: Long, batchId: Long) {
        accounts.find(userId, accountId) ?: throw NoSuchElementException("계좌를 찾을 수 없습니다")
        if (!batches.delete(accountId, batchId)) throw NoSuchElementException("가져오기 기록을 찾을 수 없습니다")
    }

    // ── 계산 ────────────────────────────────────────────────────────────────

    private data class Evaluation(val rows: List<Evaluated>, val charset: String, val format: CsvFormat)

    private fun evaluate(userId: Long, accountId: Long, file: ByteArray, options: ImportOptions): Evaluation {
        val account = accounts.find(userId, accountId) ?: throw NoSuchElementException("계좌를 찾을 수 없습니다")
        require(account.source == AccountSource.CSV) { "파일 업로드 계좌에만 가져올 수 있습니다" }
        require(consents.missingRequired(userId, ConsentGroup.PORTFOLIO_DATA).isEmpty()) { "계좌 정보 수집·이용 동의가 필요합니다" }
        require(file.isNotEmpty()) { "파일이 비어 있습니다" }
        require(file.size <= MAX_FILE_BYTES) { "파일은 ${MAX_FILE_BYTES / 1024 / 1024}MB까지입니다" }

        val format = format(options)
        val parsed = TransactionCsvParser.parse(file, format)
        val stocks = resolveStocks(parsed.lines.mapNotNull { it.row?.symbol }.distinct())
        val existing = transactions.fingerprints(accountId)
        val occurrences = HashMap<String, Int>()

        val rows = parsed.lines.map { pl ->
            val row = pl.row ?: return@map Evaluated(pl.line, errorResult(pl.line, null, pl.error), null)
            try {
                val stock = row.symbol?.let { pickStock(stocks[it], row.currency) }
                val tx = normalizer.normalize(
                    TransactionInput(
                        type = row.type, tradeDate = row.tradeDate,
                        stockId = stock?.id, instrumentCode = if (stock == null) row.symbol else null, instrumentName = row.name,
                        quantity = row.quantity, price = row.price, amount = row.amount, fee = row.fee, tax = row.tax,
                        // 파일에 적힌 통화 → 맞춘 종목의 통화 → 형식의 기본 통화. 적힌 통화가 종목과 다르면 normalize가 거부한다
                        currency = row.currency ?: stock?.currency ?: format.defaultCurrency,
                        memo = row.memo,
                    ),
                )
                val content = contentKey(tx)
                val n = occurrences.merge(content, 1, Int::plus)!!
                val fingerprint = sha256("csv:$content#$n".toByteArray())
                val withFp = tx.copy(fingerprint = fingerprint)
                val status = if (fingerprint in existing) ImportRowStatus.DUPLICATE else ImportRowStatus.NEW
                Evaluated(pl.line, result(pl.line, status, row, withFp, null), withFp)
            } catch (e: IllegalArgumentException) {
                Evaluated(pl.line, errorResult(pl.line, row, e.message), null)
            }
        }
        return Evaluation(rows, parsed.charset, format)
    }

    private fun format(options: ImportOptions): CsvFormat {
        val currency = options.defaultCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.also {
            require(it.matches(Regex("[A-Z]{3}"))) { "기본 통화는 세 글자 코드입니다" }
        }
        return when (options.format) {
            CsvFormat.MONTICKER_V1.id -> CsvFormat.MONTICKER_V1.let { if (currency != null) it.copy(defaultCurrency = currency) else it }
            "CUSTOM" -> {
                val columns = requireNotNull(options.columns) { "열 매핑이 필요합니다" }
                require(columns.values.all { it.isNotBlank() && it.length <= 100 }) { "열 이름이 올바르지 않습니다" }
                CsvFormat(
                    id = "CUSTOM",
                    headers = columns.mapValues { listOf(it.value) },
                    typeAliases = CsvFormat.DEFAULT_TYPE_ALIASES + (options.typeValues ?: emptyMap()).mapKeys { CsvFormat.normalizeKey(it.key) },
                    defaultCurrency = currency ?: "KRW",
                )
            }
            else -> throw IllegalArgumentException("알 수 없는 형식: ${options.format}")
        }
    }

    private data class StockRef(val id: Long, val currency: String)

    private fun resolveStocks(symbols: List<String>): Map<String, List<StockRef>> {
        if (symbols.isEmpty()) return emptyMap()
        return symbols.chunked(1000).flatMap { chunk ->
            jdbc.query(
                "SELECT id, symbol, currency FROM stocks WHERE upper(symbol) IN (${chunk.joinToString(",") { "?" }})",
                { rs, _ -> rs.getString("symbol").uppercase() to StockRef(rs.getLong("id"), rs.getString("currency")) },
                *chunk.toTypedArray(),
            )
        }.groupBy({ it.first }, { it.second })
    }

    /** 같은 코드가 여러 종목이면 통화로 고른다. 그래도 하나로 안 좁혀지면 매칭하지 않는다(코드 그대로) */
    private fun pickStock(candidates: List<StockRef>?, currency: String?): StockRef? = when {
        candidates.isNullOrEmpty() -> null
        candidates.size == 1 -> candidates.single()
        else -> candidates.filter { it.currency == currency }.singleOrNull()
    }

    private fun contentKey(t: NewTransaction): String = listOf(
        t.type, t.tradeDate, t.stockId?.let { "s$it" } ?: "c${t.instrumentCode ?: ""}",
        t.quantity.plain(), t.price.plain(), t.amount.plain(), t.fee.plain(), t.tax.plain(), t.currency,
    ).joinToString("|")

    private fun BigDecimal?.plain() = this?.stripTrailingZeros()?.toPlainString() ?: ""

    private fun result(line: Int, status: ImportRowStatus, row: CsvRow, tx: NewTransaction, message: String?) = ImportRowResult(
        line = line, status = status, type = tx.type, tradeDate = tx.tradeDate, symbol = row.symbol, name = row.name, stockId = tx.stockId,
        quantity = tx.quantity, price = tx.price, amount = tx.amount, fee = tx.fee, tax = tx.tax, currency = tx.currency, message = message,
    )

    private fun errorResult(line: Int, row: CsvRow?, message: String?) = ImportRowResult(
        line = line, status = ImportRowStatus.ERROR, type = row?.type, tradeDate = row?.tradeDate, symbol = row?.symbol, name = row?.name,
        stockId = null, quantity = row?.quantity, price = row?.price, amount = row?.amount, fee = row?.fee, tax = row?.tax,
        currency = row?.currency, message = message ?: "읽을 수 없는 행입니다",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILE_BYTES = 5 * 1024 * 1024
        const val PREVIEW_ROWS = 500
    }
}
