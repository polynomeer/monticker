package com.monticker.api.portfolio.domain.csv

import com.monticker.api.portfolio.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDate

/**
 * 거래내역 CSV 파싱(ADR-106 1단계). 실제 파일에서 흔한 것들 — 엑셀이 저장한 CP949, UTF-8 BOM, 한글 헤더, 따옴표 안 쉼표,
 * 천 단위 쉼표·통화 기호·괄호 음수, 여러 날짜 표기, 엑셀이 지운 종목 코드 앞자리 0 — 을 본다.
 */
class TransactionCsvParserTest {

    private val fmt = CsvFormat.MONTICKER_V1
    private fun parse(text: String, charset: Charset = Charsets.UTF_8, format: CsvFormat = fmt) = TransactionCsvParser.parse(text.toByteArray(charset), format)

    @Test
    fun `표준 템플릿을 그대로 읽는다`() {
        val lines = parse(CsvFormat.TEMPLATE).lines
        assertThat(lines).allSatisfy { assertThat(it.error).isNull() }
        assertThat(lines.map { it.row!!.type }).containsExactly(TransactionType.BUY, TransactionType.SELL, TransactionType.DIVIDEND, TransactionType.BUY)
        val sell = lines[1].row!!
        assertThat(sell.tradeDate).isEqualTo(LocalDate.of(2025, 2, 3))
        // 템플릿 예시는 미래 날짜가 아니어야 한다 — 그대로 올려도 오류가 없어야 한다
        assertThat(lines.map { it.row!!.tradeDate }).allMatch { it.isBefore(LocalDate.of(2026, 1, 1)) }
        assertThat(sell.symbol).isEqualTo("005930")
        assertThat(sell.quantity).isEqualByComparingTo("4")
        assertThat(sell.tax).isEqualByComparingTo("546")
        assertThat(lines[2].row!!.amount).isEqualByComparingTo("3610")
        assertThat(lines[2].row!!.memo).isEqualTo("분기배당")
        assertThat(lines[3].row!!.currency).isEqualTo("USD")
        assertThat(lines.map { it.line }).containsExactly(2, 3, 4, 5)
    }

    @Test
    fun `엑셀이 저장한 CP949 한글 헤더 파일을 읽는다`() {
        val text = "거래일자,거래구분,종목코드,종목명,수량,단가,수수료\r\n2026.10.01,매수,A005930,삼성전자,10,\"70,000\",100\r\n"
        val parsed = parse(text, Charset.forName("MS949"))
        assertThat(parsed.charset).isEqualTo("CP949")
        val row = parsed.lines.single().row!!
        assertThat(row.type).isEqualTo(TransactionType.BUY)
        assertThat(row.symbol).isEqualTo("005930")          // A 접두어 제거
        assertThat(row.name).isEqualTo("삼성전자")
        assertThat(row.price).isEqualByComparingTo("70000")  // 따옴표 안 천 단위 쉼표
        assertThat(row.currency).isNull()                    // 비면 종목 통화로(서비스가 정한다)
    }

    @Test
    fun `UTF-8 BOM과 탭 구분 파일을 읽는다`() {
        val text = "﻿date\ttype\tsymbol\tquantity\tprice\n2026-10-01\tBUY\tAAPL\t0.5\t280.10\n"
        val parsed = parse(text)
        assertThat(parsed.charset).isEqualTo("UTF-8")
        assertThat(parsed.lines.single().row!!.symbol).isEqualTo("AAPL")
    }

    @Test
    fun `숫자의 쉼표·공백·통화 기호·부호를 벗긴다 — 방향은 거래 구분이 정한다`() {
        assertThat(TransactionCsvParser.parseNumber("₩1,234,567", "x")).isEqualByComparingTo("1234567")
        assertThat(TransactionCsvParser.parseNumber("$ 280.10", "x")).isEqualByComparingTo("280.10")
        assertThat(TransactionCsvParser.parseNumber("-70,000원", "x")).isEqualByComparingTo("70000")
        assertThat(TransactionCsvParser.parseNumber("(1,500)", "x")).isEqualByComparingTo("1500")
        assertThatThrownBy { TransactionCsvParser.parseNumber("abc", "수량") }.hasMessageContaining("수량")
    }

    @Test
    fun `여러 날짜 표기를 읽고 시각은 버린다`() {
        listOf("2026-10-01", "2026.10.01", "2026/10/1", "20261001", "2026. 10. 1.", "2026-10-01 09:12:33", "2026.10.01 09:12", "2026-10-01T09:12:33").forEach {
            assertThat(TransactionCsvParser.parseDate(it)).`as`(it).isEqualTo(LocalDate.of(2026, 10, 1))
        }
        assertThatThrownBy { TransactionCsvParser.parseDate("2026-13-01") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TransactionCsvParser.parseDate("10/01/2026") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `엑셀이 지운 국내 종목 코드 앞자리 0을 되살리고 해외 티커는 대문자로`() {
        assertThat(TransactionCsvParser.normalizeSymbol("5930")).isEqualTo("005930")
        assertThat(TransactionCsvParser.normalizeSymbol("A000660")).isEqualTo("000660")
        assertThat(TransactionCsvParser.normalizeSymbol(" aapl ")).isEqualTo("AAPL")
        assertThat(TransactionCsvParser.normalizeSymbol("KR5223941K16")).isEqualTo("KR5223941K16")
    }

    @Test
    fun `읽을 수 없는 행은 그 행만 오류이고 나머지는 읽는다`() {
        val text = "date,type,symbol,quantity,price\n2026-10-01,BUY,005930,1,100\n2026-10-02,환전,005930,1,100\n,BUY,005930,1,100\n2026-10-03,SELL,005930,x,100\n"
        val lines = parse(text).lines
        assertThat(lines.map { it.error == null }).containsExactly(true, false, false, false)
        assertThat(lines[1].error).contains("알 수 없는 거래 구분: 환전")
        assertThat(lines[2].error).contains("날짜")
        assertThat(lines[3].error).contains("수량")
        assertThat(lines[3].line).isEqualTo(5)
    }

    @Test
    fun `따옴표 안의 쉼표·줄바꿈·이스케이프를 지키고 빈 줄은 건너뛴다`() {
        val text = "date,type,symbol,quantity,price,memo\r\n\r\n2026-10-01,BUY,005930,1,100,\"메모, 쉼표\"\r\n2026-10-02,BUY,005930,1,100,\"두 줄\n메모 \"\"인용\"\"\"\r\n"
        val lines = parse(text).lines
        assertThat(lines.map { it.row!!.memo }).containsExactly("메모, 쉼표", "두 줄\n메모 \"인용\"")
        assertThat(lines.map { it.line }).containsExactly(3, 4)
    }

    @Test
    fun `필수 열이 없거나 따옴표가 닫히지 않으면 파일 전체를 거부한다`() {
        assertThatThrownBy { parse("symbol,quantity\n005930,1\n") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("필수 열")
        assertThatThrownBy { parse("date,type\n2026-10-01,\"BUY\n") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("따옴표")
        assertThatThrownBy { parse("") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `열 매핑(CUSTOM)과 거래 구분 값을 직접 줄 수 있다`() {
        val custom = CsvFormat(
            id = "CUSTOM",
            headers = mapOf(CsvField.DATE to listOf("체결일"), CsvField.TYPE to listOf("매매"), CsvField.SYMBOL to listOf("종목번호"),
                CsvField.QUANTITY to listOf("체결수량"), CsvField.PRICE to listOf("체결단가")),
            typeAliases = CsvFormat.DEFAULT_TYPE_ALIASES + mapOf(CsvFormat.normalizeKey("주식매수") to TransactionType.BUY),
        )
        val row = parse("체결일,매매,종목번호,체결수량,체결단가\n20261001,주식매수,000660,3,\"180,000\"\n", format = custom).lines.single().row!!
        assertThat(row.type).isEqualTo(TransactionType.BUY)
        assertThat(row.price).isEqualTo(BigDecimal("180000"))
    }

    @Test
    fun `한 번에 2만 행까지다`() {
        val text = buildString {
            append("date,type,amount\n")
            repeat(TransactionCsvParser.MAX_DATA_ROWS + 1) { append("2026-10-01,DEPOSIT,1\n") }
        }
        assertThatThrownBy { parse(text) }.hasMessageContaining("20000")
    }
}
