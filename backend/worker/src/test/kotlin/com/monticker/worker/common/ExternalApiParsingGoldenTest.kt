package com.monticker.worker.common

import com.monticker.worker.disclosure.DartClient
import com.monticker.worker.disclosure.DartDisclosure
import com.monticker.worker.kis.KisClient
import com.monticker.worker.kis.KisInvestorDay
import com.monticker.worker.news.NaverNewsClient
import com.monticker.worker.push.ExpoPushSender
import com.monticker.worker.push.PushMessage
import com.monticker.worker.push.PushResult
import com.monticker.worker.stock.KrxStockClient
import com.monticker.worker.stock.KrxStockItem
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 외부 API 응답(트리 모델로 읽는 곳)을 어떻게 해석하는지 고정한다 — Jackson 2 → 3 전환(B4-6) 전에 쓰고, 전환 뒤에도 그대로여야 한다.
 *
 * Jackson 3는 트리 접근의 의미가 다르다: `asLong()` 등이 숫자가 아닌 문자열·빈 문자열에서 예외를 던지고(Jackson 2는 0),
 * JSON `null`의 `asText()`가 `""`다(Jackson 2는 문자열 `"null"`), 3.1의 `JsonNode.map(Function)`이 Kotlin `map`을 가린다.
 * 실제 응답 모양 — 숫자를 문자열로 보내는 KIS, 빈 문자열, 빠진 필드, JSON null — 을 넣는다.
 */
class ExternalApiParsingGoldenTest {

    private val cb = CircuitBreakerRegistry.ofDefaults()

    // ── KIS ──────────────────────────────────────────────────────────────────

    private val kis = KisClient("k", "s", cb)

    @Test
    fun `KIS 현재가 — 숫자는 문자열로 오고 빈 값은 빈 문자열이다`() {
        val body = """{"rt_cd":"0","msg_cd":"MCA00000","output":{"stck_prpr":"71200","acml_vol":"12345678","stck_hgpr":"71900",
            |"stck_lwpr":"70500","stck_oprc":"70800","per":"13.21","pbr":"1.10","eps":"","bps":null,"hts_avls":"4250000"}}""".trimMargin()
        val p = kis.parsePrice("005930", body)!!
        assertThat(p.price).isEqualTo(BigDecimal("71200"))
        assertThat(p.volume).isEqualTo(12_345_678L)
        assertThat(listOf(p.high, p.low, p.open)).containsExactly(BigDecimal("71900"), BigDecimal("70500"), BigDecimal("70800"))
        assertThat(p.per).isEqualTo(BigDecimal("13.21"))
        assertThat(p.eps).isNull()     // "" → null
        assertThat(p.bps).isNull()     // JSON null → null
        assertThat(p.marketCapEok).isEqualTo(4_250_000L)
    }

    @Test
    fun `KIS 현재가 — 실패 코드·가격 없음·숫자 아닌 값`() {
        assertThat(kis.parsePrice("005930", """{"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다."}""")).isNull()
        assertThat(kis.parsePrice("005930", """{"rt_cd":"0","output":{"stck_prpr":""}}""")).isNull()
        val p = kis.parsePrice("005930", """{"rt_cd":"0","output":{"stck_prpr":"100","acml_vol":"abc","stck_hgpr":""}}""")!!
        assertThat(p.volume).isZero()
        assertThat(p.high).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun `KIS 투자자 동향 — 날짜가 잘못된 행은 건너뛰고 빈 숫자는 0`() {
        val body = """{"rt_cd":"0","output":[
            |{"stck_bsop_date":"20261016","prsn_ntby_qty":"-1200","frgn_ntby_qty":"800","orgn_ntby_qty":"400",
            | "prsn_ntby_tr_pbmn":"-85","frgn_ntby_tr_pbmn":"57","orgn_ntby_tr_pbmn":""},
            |{"stck_bsop_date":"","prsn_ntby_qty":"1"},
            |{"stck_bsop_date":"20261015","prsn_ntby_qty":null}]}""".trimMargin()
        assertThat(kis.parseInvestorTrend(body)).containsExactly(
            KisInvestorDay(LocalDate.of(2026, 10, 16), -1200, 800, 400, -85, 57, 0),
            KisInvestorDay(LocalDate.of(2026, 10, 15), 0, 0, 0, 0, 0, 0),
        )
    }

    // ── DART ─────────────────────────────────────────────────────────────────

    @Test
    fun `DART 공시 목록 — 비상장(빈 종목코드)은 뺀다`() {
        val body = """{"status":"000","message":"정상","list":[
            |{"rcept_no":"20261016000123","corp_name":"삼성전자","stock_code":"005930","report_nm":"주요사항보고서","rcept_dt":"20261016"},
            |{"rcept_no":"20261016000124","corp_name":"비상장사","stock_code":" ","report_nm":"감사보고서","rcept_dt":"20261016"},
            |{"rcept_no":"20261016000125","corp_name":"누락","report_nm":"x","rcept_dt":"20261016"}]}""".trimMargin()
        assertThat(DartClient("key", cb).parseList(body)).containsExactly(
            DartDisclosure("20261016000123", "삼성전자", "005930", "주요사항보고서", "20261016"),
        )
        assertThat(DartClient("key", cb).parseList("""{"status":"013","message":"조회된 데이타가 없습니다."}""")).isEmpty()
    }

    @Test
    fun `DART 공시 — 값이 JSON null인 필드`() {
        val body = """{"status":"000","list":[{"rcept_no":"1","corp_name":null,"stock_code":"005930","report_nm":"r","rcept_dt":"20261016"}]}"""
        assertThat(DartClient("key", cb).parseList(body).single().corpName).isEqualTo(NULL_TEXT)
    }

    // ── Naver ────────────────────────────────────────────────────────────────

    @Test
    fun `Naver 뉴스 — 태그와 엔티티를 벗기고 출처 도메인을 뽑는다`() {
        val body = """{"lastBuildDate":"Fri, 16 Oct 2026 10:00:00 +0900","total":2,"start":1,"display":2,"items":[
            |{"title":"<b>삼성전자</b> &quot;반도체&quot; 호조","originallink":"https://www.hankyung.com/article/1","link":"https://n.news.naver.com/1",
            | "description":"3분기 &amp; 4분기 <b>실적</b>","pubDate":"Fri, 16 Oct 2026 09:30:00 +0900"},
            |{"title":"두번째","originallink":"https://news.mt.co.kr/a","link":"https://n.news.naver.com/2","description":"","pubDate":"Fri, 16 Oct 2026 09:00:00 +0900"}]}""".trimMargin()
        val items = NaverNewsClient("id", "secret", cb).parseItems(body)
        assertThat(items.map { it.title }).containsExactly("삼성전자 \"반도체\" 호조", "두번째")
        assertThat(items.first().description).isEqualTo("3분기 & 4분기 실적")
        assertThat(items.map { it.link }).containsExactly("https://n.news.naver.com/1", "https://n.news.naver.com/2")
        assertThat(items.map { it.source }).containsExactly("hankyung.com", "news.mt.co.kr")
        assertThat(items.last().description).isEmpty()
        assertThat(NaverNewsClient("id", "secret", cb).parseItems("""{"total":0}""")).isEmpty()
    }

    @Test
    fun `Naver 뉴스 — 값이 JSON null인 필드`() {
        val body = """{"items":[{"title":"t","originallink":"https://a.com/x","link":"l","description":null,"pubDate":"p"}]}"""
        assertThat(NaverNewsClient("id", "secret", cb).parseItems(body).single().description).isEqualTo(NULL_TEXT)
    }

    // ── KRX ──────────────────────────────────────────────────────────────────

    @Test
    fun `KRX 종목 — 코드·이름이 비면 빼고 빈 업종은 null`() {
        val body = """{"OutBlock_1":[
            |{"ISU_SRT_CD":"005930","ISU_NM":"삼성전자 ","SECT_TP_NM":"전기전자"},
            |{"ISU_SRT_CD":"000660","ISU_NM":"SK하이닉스","SECT_TP_NM":""},
            |{"ISU_SRT_CD":"","ISU_NM":"빈코드"},
            |{"ISU_NM":"코드없음"}]}""".trimMargin()
        assertThat(KrxStockClient().parseItems(body, "KOSPI")).containsExactly(
            KrxStockItem("005930", "삼성전자", "KOSPI", "전기전자"),
            KrxStockItem("000660", "SK하이닉스", "KOSPI", null),
        )
    }

    @Test
    fun `KRX 종목 — 업종이 JSON null`() {
        val body = """{"OutBlock_1":[{"ISU_SRT_CD":"005930","ISU_NM":"삼성전자","SECT_TP_NM":null}]}"""
        assertThat(KrxStockClient().parseItems(body, "KOSPI").single().sector).isEqualTo(NULL_SECTOR)
    }

    // ── Expo ─────────────────────────────────────────────────────────────────

    private val expo = ExpoPushSender(cb)
    private val two = listOf(PushMessage(to = "ExponentPushToken[a]", title = "t", body = "b"), PushMessage(to = "ExponentPushToken[b]", title = "t", body = "b"))

    @Test
    fun `Expo 응답 — 배열·단일·data 없음`() {
        val body = """{"data":[{"status":"ok","id":"x1"},{"status":"error","message":"\"ExponentPushToken[b]\" is not a registered push notification recipient","details":{"error":"DeviceNotRegistered"}}]}"""
        assertThat(expo.parseResults(two, body)).containsExactly(
            PushResult("ExponentPushToken[a]", "ok", null),
            PushResult("ExponentPushToken[b]", "error", "\"ExponentPushToken[b]\" is not a registered push notification recipient"),
        )
        assertThat(expo.parseResults(two.take(1), """{"data":{"status":"ok","id":"x"}}""")).containsExactly(PushResult("ExponentPushToken[a]", "ok", null))
        assertThat(expo.parseResults(two.take(1), """{"errors":[{"code":"PUSH_TOO_MANY_EXPERIENCE_IDS"}]}"""))
            .containsExactly(PushResult("ExponentPushToken[a]", "error", "no data"))
    }

    companion object {
        /** JSON null 필드를 asText()로 읽은 결과 — Jackson 2는 문자열 "null"을 돌려준다 */
        const val NULL_TEXT = "null"
        val NULL_SECTOR: String? = "null"
    }
}
