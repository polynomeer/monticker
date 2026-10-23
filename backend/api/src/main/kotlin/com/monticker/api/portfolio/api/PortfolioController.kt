package com.monticker.api.portfolio.api

import com.monticker.api.portfolio.application.HoldingEventFeed
import com.monticker.api.portfolio.application.HoldingsScope
import com.monticker.api.portfolio.application.HoldingsView
import com.monticker.api.portfolio.application.ImportOptions
import com.monticker.api.portfolio.application.ImportPreview
import com.monticker.api.portfolio.application.ImportResult
import com.monticker.api.portfolio.application.PortfolioImportService
import com.monticker.api.portfolio.application.TransactionInput
import com.monticker.api.portfolio.domain.ImportBatch
import com.monticker.api.portfolio.domain.csv.CsvFormat
import com.monticker.api.portfolio.application.PortfolioAccountService
import com.monticker.api.portfolio.application.PortfolioAnalysis
import com.monticker.api.portfolio.application.PortfolioAnalysisService
import com.monticker.api.portfolio.application.PortfolioEventFeedService
import com.monticker.api.portfolio.application.PortfolioHoldingsQueryService
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.PortfolioAccount
import com.monticker.api.portfolio.domain.PortfolioTransaction
import com.monticker.api.portfolio.domain.TransactionType
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 통합 포트폴리오(ADR-106, docs/account-aggregation-plan.md 1단계). 손 입력·CSV 가져오기 계좌와 통합 보유.
 * 남의 계좌·거래는 없는 것과 같이 404다. 첫 계좌를 만들 때 "계좌 정보 수집·이용" 동의를 받는다.
 */
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val accountService: PortfolioAccountService,
    private val holdingsService: PortfolioHoldingsQueryService,
    private val eventFeedService: PortfolioEventFeedService,
    private val analysisService: PortfolioAnalysisService,
    private val importService: PortfolioImportService,
) {
    /** GET /api/portfolio/holdings?scope=REAL|PAPER — REAL은 모의계좌를 뺀 모든 계좌의 합 */
    @GetMapping("/holdings")
    fun holdings(@AuthenticationPrincipal userId: Long, @RequestParam(defaultValue = "REAL") scope: HoldingsScope): HoldingsView =
        holdingsService.holdings(userId, scope)

    /**
     * GET /api/portfolio/events?cursor=&limit=20 — 모든 실계좌의 보유 종목 이벤트, 최신순(ADR-108).
     * 다음 페이지는 응답의 nextCursor를 cursor로 넘긴다.
     */
    @GetMapping("/events")
    fun events(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): HoldingEventFeed = eventFeedService.feed(userId, cursor, limit.coerceIn(1, 50))

    /** GET /api/portfolio/analysis — 원화 요약(주가·환차손익), 배분, 배당, 돈 가중 수익률(ADR-111) */
    @GetMapping("/analysis")
    fun analysis(@AuthenticationPrincipal userId: Long): PortfolioAnalysis = analysisService.analysis(userId)

    @GetMapping("/accounts")
    fun accounts(@AuthenticationPrincipal userId: Long): List<PortfolioAccountResponse> =
        accountService.list(userId).map(PortfolioAccountResponse::from)

    /**
     * 손 입력(MANUAL) 또는 파일 업로드(CSV) 계좌를 만든다. 계좌번호는 끝 4자리만 받는다.
     * 아직 "계좌 정보 수집·이용"(PORTFOLIO_DATA_COLLECTION)에 동의하지 않았으면 consents에 담아야 한다 — 없으면 400.
     */
    @PostMapping("/accounts")
    fun create(@AuthenticationPrincipal userId: Long, @RequestBody req: CreateAccountRequest): ResponseEntity<PortfolioAccountResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            PortfolioAccountResponse.from(
                accountService.create(userId, req.source, req.name, req.institution, req.accountKind, req.accountRefLast4, req.consents),
            ),
        )

    /** "계좌 정보 수집·이용"에 다시 동의한다(문서가 바뀐 뒤). body: {"consents": ["PORTFOLIO_DATA_COLLECTION"]} */
    @PostMapping("/consent")
    fun agreeConsent(@AuthenticationPrincipal userId: Long, @RequestBody req: PortfolioConsentRequest): ResponseEntity<Void> {
        accountService.agreeConsent(userId, req.consents)
        return ResponseEntity.noContent().build()
    }

    /** 통합 포트폴리오 데이터를 모두 지우고 동의를 철회한다(되돌릴 수 없다). 모의계좌·증권사 주문 연동은 그대로다 */
    @DeleteMapping("/data")
    fun deleteAll(@AuthenticationPrincipal userId: Long): Map<String, Int> = mapOf("deletedAccounts" to accountService.deleteAll(userId))

    /** 표준 템플릿(CSV, UTF-8 BOM — 엑셀에서 한글이 깨지지 않게) */
    @GetMapping("/imports/template", produces = ["text/csv"])
    fun template(): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"monticker-portfolio-template.csv\"")
            .contentType(MediaType("text", "csv", Charsets.UTF_8))
            .body(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + CsvFormat.TEMPLATE.toByteArray())

    /** 미리보기 — 저장하지 않는다. 같은 파일과 options로 확정한다 */
    @PostMapping("/accounts/{accountId}/imports/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun previewImport(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long,
        @RequestPart("file") file: MultipartFile, @RequestPart("options", required = false) options: ImportOptions?,
    ): ImportPreview = importService.preview(userId, accountId, file.bytes, options ?: ImportOptions())

    /** 확정 — 오류 행이 있으면 skipErrors=true일 때만 그 행을 빼고 넣는다. expectedSha256은 미리보기의 fileSha256 */
    @PostMapping("/accounts/{accountId}/imports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun commitImport(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long,
        @RequestPart("file") file: MultipartFile, @RequestPart("options", required = false) options: ImportOptions?,
        @RequestParam(defaultValue = "false") skipErrors: Boolean, @RequestParam(required = false) expectedSha256: String?,
    ): ResponseEntity<ImportResult> = ResponseEntity.status(HttpStatus.CREATED).body(
        importService.commit(userId, accountId, file.bytes, options ?: ImportOptions(), skipErrors, expectedSha256),
    )

    @GetMapping("/accounts/{accountId}/imports")
    fun imports(@AuthenticationPrincipal userId: Long, @PathVariable accountId: Long): List<ImportBatch> = importService.list(userId, accountId)

    /** 가져오기 한 번을 되돌린다 — 그때 들어온 거래만 지운다 */
    @DeleteMapping("/accounts/{accountId}/imports/{batchId}")
    fun revertImport(@AuthenticationPrincipal userId: Long, @PathVariable accountId: Long, @PathVariable batchId: Long): ResponseEntity<Void> {
        importService.revert(userId, accountId, batchId)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/accounts/{accountId}")
    fun update(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long, @RequestBody req: UpdateAccountRequest,
    ): PortfolioAccountResponse = PortfolioAccountResponse.from(accountService.update(userId, accountId, req.name, req.accountKind))

    /** 계좌와 거래를 함께 지운다(되돌릴 수 없다) */
    @DeleteMapping("/accounts/{accountId}")
    fun delete(@AuthenticationPrincipal userId: Long, @PathVariable accountId: Long): ResponseEntity<Void> {
        accountService.delete(userId, accountId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/accounts/{accountId}/transactions")
    fun transactions(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int,
    ): List<PortfolioTransactionResponse> =
        accountService.transactions(userId, accountId, page, size).map(PortfolioTransactionResponse::from)

    @PostMapping("/accounts/{accountId}/transactions")
    fun addTransaction(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long, @RequestBody req: AddTransactionRequest,
    ): ResponseEntity<Map<String, Long>> =
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to accountService.addManual(userId, accountId, req.toInput())))

    @DeleteMapping("/accounts/{accountId}/transactions/{transactionId}")
    fun deleteTransaction(
        @AuthenticationPrincipal userId: Long, @PathVariable accountId: Long, @PathVariable transactionId: Long,
    ): ResponseEntity<Void> {
        accountService.deleteTransaction(userId, accountId, transactionId)
        return ResponseEntity.noContent().build()
    }
}

data class CreateAccountRequest(
    val name: String,
    val institution: Institution,
    val source: AccountSource = AccountSource.MANUAL,
    val accountKind: AccountKind = AccountKind.GENERAL,
    val accountRefLast4: String? = null,
    /** 처음이면 ["PORTFOLIO_DATA_COLLECTION"] */
    val consents: List<String> = emptyList(),
)

data class PortfolioConsentRequest(val consents: List<String> = emptyList())

data class UpdateAccountRequest(val name: String, val accountKind: AccountKind)

data class AddTransactionRequest(
    val type: TransactionType,
    val tradeDate: LocalDate,
    val stockId: Long? = null,
    val instrumentCode: String? = null,
    val instrumentName: String? = null,
    val quantity: BigDecimal? = null,
    val price: BigDecimal? = null,
    val amount: BigDecimal? = null,
    val fee: BigDecimal? = null,
    val tax: BigDecimal? = null,
    val currency: String? = null,
    val memo: String? = null,
) {
    fun toInput() = TransactionInput(
        type = type, tradeDate = tradeDate, stockId = stockId, instrumentCode = instrumentCode, instrumentName = instrumentName,
        quantity = quantity, price = price, amount = amount, fee = fee ?: BigDecimal.ZERO, tax = tax ?: BigDecimal.ZERO,
        currency = currency, memo = memo,
    )
}

data class PortfolioAccountResponse(
    val id: Long,
    val source: AccountSource,
    val institution: Institution,
    val name: String,
    val accountKind: AccountKind,
    val accountRefMasked: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(a: PortfolioAccount) = PortfolioAccountResponse(a.id, a.source, a.institution, a.name, a.accountKind, a.accountRefMasked, a.createdAt)
    }
}

data class PortfolioTransactionResponse(
    val id: Long,
    val type: TransactionType,
    val tradeDate: LocalDate,
    val stockId: Long?,
    val instrumentCode: String?,
    val instrumentName: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    val amount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val currency: String,
    val memo: String?,
) {
    companion object {
        fun from(t: PortfolioTransaction) = PortfolioTransactionResponse(
            t.id, t.type, t.tradeDate, t.stockId, t.instrumentCode, t.instrumentName, t.quantity, t.price, t.amount, t.fee, t.tax, t.currency, t.memo,
        )
    }
}
