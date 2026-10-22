package com.monticker.api.portfolio.domain.csv

import com.monticker.api.portfolio.domain.TransactionType
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

/** 거래 한 행에서 읽어야 하는 필드 */
enum class CsvField { DATE, TYPE, SYMBOL, NAME, QUANTITY, PRICE, AMOUNT, FEE, TAX, CURRENCY, MEMO }

/**
 * 파일 형식 — 필드마다 받아들이는 헤더 이름들, 거래 구분 값 → 종류, 기본 통화.
 * 증권사별 형식은 실제 내보내기 파일을 받은 뒤 여기에 더한다(형식을 추측해 만들지 않는다).
 */
data class CsvFormat(
    val id: String,
    val headers: Map<CsvField, List<String>>,
    val typeAliases: Map<String, TransactionType>,
    val defaultCurrency: String = "KRW",
) {
    init {
        require(CsvField.DATE in headers && CsvField.TYPE in headers) { "날짜·거래 구분 열은 반드시 있어야 합니다" }
    }

    companion object {
        /** 기본 거래 구분 값(대소문자·공백 무시) */
        val DEFAULT_TYPE_ALIASES: Map<String, TransactionType> = buildMap {
            TransactionType.entries.forEach { put(it.name, it) }
            listOf("매수", "현금매수", "장내매수", "매수체결").forEach { put(it, TransactionType.BUY) }
            listOf("매도", "현금매도", "장내매도", "매도체결").forEach { put(it, TransactionType.SELL) }
            listOf("배당", "배당금", "배당금입금", "현금배당").forEach { put(it, TransactionType.DIVIDEND) }
            listOf("입금", "이체입금", "예수금입금").forEach { put(it, TransactionType.DEPOSIT) }
            listOf("출금", "이체출금", "예수금출금").forEach { put(it, TransactionType.WITHDRAWAL) }
            listOf("수수료").forEach { put(it, TransactionType.FEE) }
            listOf("세금", "제세금").forEach { put(it, TransactionType.TAX) }
        }.mapKeys { normalizeKey(it.key) }

        /** monticker 표준 템플릿 — 영문·한글 헤더 둘 다 받는다(GET /api/portfolio/imports/template) */
        val MONTICKER_V1 = CsvFormat(
            id = "MONTICKER_V1",
            headers = mapOf(
                CsvField.DATE to listOf("date", "날짜", "거래일", "거래일자"),
                CsvField.TYPE to listOf("type", "구분", "거래구분", "거래종류"),
                CsvField.SYMBOL to listOf("symbol", "종목코드", "코드"),
                CsvField.NAME to listOf("name", "종목명"),
                CsvField.QUANTITY to listOf("quantity", "수량"),
                CsvField.PRICE to listOf("price", "단가", "가격"),
                CsvField.AMOUNT to listOf("amount", "금액", "거래금액"),
                CsvField.FEE to listOf("fee", "수수료"),
                CsvField.TAX to listOf("tax", "세금", "제세금"),
                CsvField.CURRENCY to listOf("currency", "통화"),
                CsvField.MEMO to listOf("memo", "메모", "비고"),
            ),
            typeAliases = DEFAULT_TYPE_ALIASES,
        )

        /** 예시 날짜는 지난 해로 둔다 — 미래 날짜는 거부되므로, 오늘보다 뒤인 예시는 템플릿을 그대로 올리면 오류가 난다 */
        const val TEMPLATE = "date,type,symbol,name,quantity,price,amount,fee,tax,currency,memo\r\n" +
            "2025-01-02,BUY,005930,삼성전자,10,70000,,100,,KRW,\r\n" +
            "2025-02-03,SELL,005930,삼성전자,4,78000,,100,546,KRW,\r\n" +
            "2025-04-21,DIVIDEND,005930,삼성전자,,,3610,,555,KRW,분기배당\r\n" +
            "2025-01-03,BUY,AAPL,Apple,0.5,280.10,,,,USD,소수점\r\n"

        internal fun normalizeKey(s: String) = s.trim().replace(Regex("\\s+"), "").uppercase()
    }
}

/** 파싱된 한 행. 종목은 아직 stocks와 맞추기 전이다(코드 그대로) */
data class CsvRow(
    val type: TransactionType,
    val tradeDate: LocalDate,
    val symbol: String?,
    val name: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    val amount: BigDecimal?,
    val fee: BigDecimal,
    val tax: BigDecimal,
    /** 파일에 적힌 통화. 비어 있으면 null */
    val currency: String?,
    val memo: String?,
)

/** 파일의 줄 번호(1부터, 헤더 포함)와 결과 — 성공이면 row, 실패면 error */
data class ParsedLine(val line: Int, val row: CsvRow?, val error: String?)

data class ParsedFile(val lines: List<ParsedLine>, val charset: String)

/**
 * 거래내역 CSV → 행. 순수 함수다.
 *
 * - 인코딩: UTF-8(BOM 포함)로 읽어 보고, 깨지면 CP949(MS949)로 읽는다 — 국내 HTS 내보내기는 대개 CP949다.
 * - 구분자: 헤더에 쉼표가 없고 탭이 있으면 탭.
 * - 숫자: 천 단위 쉼표, 공백, ₩·$·원, 괄호 음수를 받는다. 부호는 버린다 — 매도·출금을 음수로 쓰는 파일이 있고, 방향은 거래 구분이 정한다.
 * - 종목 코드: 대문자로. `A005930`처럼 앞에 A가 붙은 국내 코드는 A를 떼고, 엑셀이 앞의 0을 지운 숫자 코드(`5930`)는 6자리로 채운다.
 * - 빈 줄은 건너뛴다. 헤더가 없거나 필수 열(날짜·구분)을 못 찾으면 [IllegalArgumentException].
 */
object TransactionCsvParser {

    const val MAX_DATA_ROWS = 20_000

    /** 2026-10-01, 2026.10.01, 2026/10/1, 2026. 10. 1.(엑셀 한국어), 뒤에 시각이 붙어도 된다 */
    private val SEPARATED_DATE = Regex("^(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})")
    private val COMPACT_DATE = Regex("^(\\d{4})(\\d{2})(\\d{2})(?!\\d)")

    fun parse(bytes: ByteArray, format: CsvFormat): ParsedFile {
        val (text, charset) = decode(bytes)
        val records = readRecords(text)
        require(records.isNotEmpty()) { "파일이 비어 있습니다" }
        val header = records.first().fields
        val columns = resolveColumns(header, format)
        val dataRecords = records.drop(1)
        require(dataRecords.size <= MAX_DATA_ROWS) { "한 번에 ${MAX_DATA_ROWS}행까지 가져올 수 있습니다" }

        val lines = dataRecords.map { rec ->
            try {
                ParsedLine(rec.line, parseRow(rec.fields, columns, format), null)
            } catch (e: IllegalArgumentException) {
                ParsedLine(rec.line, null, e.message)
            }
        }
        return ParsedFile(lines, charset)
    }

    // ── 인코딩 ──────────────────────────────────────────────────────────────

    internal fun decode(bytes: ByteArray): Pair<String, String> {
        val noBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(noBom)).toString() to "UTF-8"
        } catch (e: CharacterCodingException) {
            String(noBom, Charset.forName("MS949")) to "CP949"
        }
    }

    // ── CSV 레코드 ──────────────────────────────────────────────────────────

    internal data class Record(val line: Int, val fields: List<String>)

    /** RFC 4180 — 큰따옴표 안의 쉼표·줄바꿈, "" 이스케이프. 빈 줄은 건너뛴다 */
    internal fun readRecords(text: String): List<Record> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delimiter = if (!firstLine.contains(',') && firstLine.contains('\t')) '\t' else ','
        val records = mutableListOf<Record>()
        val field = StringBuilder()
        var fields = mutableListOf<String>()
        var inQuotes = false
        var line = 1
        var recordStart = 1
        var i = 0
        fun endRecord() {
            fields.add(field.toString()); field.clear()
            if (fields.any { it.isNotBlank() }) records.add(Record(recordStart, fields))
            fields = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { fields.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRecord(); line++; recordStart = line
                }
                else -> { if (c == '\n') line++; field.append(c) }
            }
            i++
        }
        require(!inQuotes) { "${recordStart}번째 줄: 닫히지 않은 따옴표가 있습니다" }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    // ── 열 찾기 ─────────────────────────────────────────────────────────────

    internal fun resolveColumns(header: List<String>, format: CsvFormat): Map<CsvField, Int> {
        val normalized = header.map { CsvFormat.normalizeKey(it) }
        val columns = format.headers.mapNotNull { (field, names) ->
            val idx = names.map { CsvFormat.normalizeKey(it) }.firstNotNullOfOrNull { n -> normalized.indexOf(n).takeIf { it >= 0 } }
            idx?.let { field to it }
        }.toMap()
        val missing = listOf(CsvField.DATE, CsvField.TYPE).filter { it !in columns }
        require(missing.isEmpty()) {
            "필수 열을 찾지 못했습니다: ${missing.joinToString { f -> format.headers.getValue(f).first() }} (헤더: ${header.joinToString(", ")})"
        }
        return columns
    }

    // ── 행 ──────────────────────────────────────────────────────────────────

    private fun parseRow(fields: List<String>, columns: Map<CsvField, Int>, format: CsvFormat): CsvRow {
        fun cell(f: CsvField): String? = columns[f]?.let { fields.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "-" }

        val rawType = cell(CsvField.TYPE) ?: throw IllegalArgumentException("거래 구분이 비어 있습니다")
        val type = format.typeAliases[CsvFormat.normalizeKey(rawType)] ?: throw IllegalArgumentException("알 수 없는 거래 구분: $rawType")
        val rawDate = cell(CsvField.DATE) ?: throw IllegalArgumentException("날짜가 비어 있습니다")
        return CsvRow(
            type = type,
            tradeDate = parseDate(rawDate),
            symbol = cell(CsvField.SYMBOL)?.let(::normalizeSymbol),
            name = cell(CsvField.NAME),
            quantity = cell(CsvField.QUANTITY)?.let { parseNumber(it, "수량") },
            price = cell(CsvField.PRICE)?.let { parseNumber(it, "단가") },
            amount = cell(CsvField.AMOUNT)?.let { parseNumber(it, "금액") },
            fee = cell(CsvField.FEE)?.let { parseNumber(it, "수수료") } ?: BigDecimal.ZERO,
            tax = cell(CsvField.TAX)?.let { parseNumber(it, "세금") } ?: BigDecimal.ZERO,
            // 비면 null — 종목을 맞춘 뒤 종목 통화, 그래도 없으면 형식의 기본 통화(가져오기 서비스가 정한다)
            currency = cell(CsvField.CURRENCY)?.uppercase(),
            memo = cell(CsvField.MEMO),
        )
    }

    internal fun parseDate(raw: String): LocalDate {
        val t = raw.trim()
        val m = SEPARATED_DATE.find(t) ?: COMPACT_DATE.find(t) ?: throw IllegalArgumentException("날짜를 읽을 수 없습니다: $raw")
        val (y, mo, d) = m.destructured
        return try { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("날짜를 읽을 수 없습니다: $raw")
        }
    }

    internal fun parseNumber(raw: String, label: String): BigDecimal {
        // 부호(-, 괄호)는 버린다 — 방향은 거래 구분이 정한다
        val cleaned = raw.replace(Regex("[,\\s₩\$원()+]"), "").removePrefix("-")
        require(cleaned.isNotEmpty()) { "$label 값이 비어 있습니다: $raw" }
        return try { BigDecimal(cleaned) } catch (e: NumberFormatException) { throw IllegalArgumentException("$label 값을 숫자로 읽을 수 없습니다: $raw") }
    }

    internal fun normalizeSymbol(raw: String): String {
        val s = raw.trim().uppercase()
        return when {
            s.matches(Regex("A\\d{6}")) -> s.substring(1)
            s.matches(Regex("\\d{1,5}")) -> s.padStart(6, '0')
            else -> s
        }
    }
}
