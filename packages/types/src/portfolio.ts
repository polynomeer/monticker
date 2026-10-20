// Aggregated portfolio (ADR-106 stage 1, ADR-108) — accounts from manual entry and CSV imports, holdings, holdings event feed.
// Mirrors backend/api/.../portfolio/api/PortfolioController.kt. Money and quantities are JSON numbers (BigDecimal on the server).

export type PortfolioAccountSource = "MANUAL" | "CSV" | "BROKER_API" | "MYDATA";
export type PortfolioAccountKind = "GENERAL" | "ISA" | "PENSION" | "IRP" | "OTHER";
export type PortfolioInstitution =
  | "KIS" | "TOSS" | "KIWOOM" | "MIRAE" | "SAMSUNG" | "NH" | "KB" | "SHINHAN" | "HANA" | "DAISHIN" | "LS" | "MERITZ"
  | "KAKAOPAY" | "IBKR" | "OTHER";
export type PortfolioTransactionType = "BUY" | "SELL" | "DIVIDEND" | "DEPOSIT" | "WITHDRAWAL" | "FEE" | "TAX";
/** REAL — 모의계좌를 뺀 모든 계좌의 합. PAPER — 모의계좌만 */
export type HoldingsScope = "REAL" | "PAPER";

/** "계좌 정보 수집·이용"(ADR-068 동의 기록). 계좌를 처음 만들 때나 문서가 바뀐 뒤 받는다 */
export type PortfolioConsent = "PORTFOLIO_DATA_COLLECTION";

export interface PortfolioAccount {
  id: number;
  source: PortfolioAccountSource;
  institution: PortfolioInstitution;
  name: string;
  accountKind: PortfolioAccountKind;
  /** 끝 4자리만 — "****1234" */
  accountRefMasked: string | null;
  createdAt: string;
}

export interface CreatePortfolioAccountRequest {
  name: string;
  institution: PortfolioInstitution;
  /** 웹은 MANUAL(손 입력)·CSV(파일 업로드)만 만든다 */
  source: "MANUAL" | "CSV";
  accountKind?: PortfolioAccountKind;
  accountRefLast4?: string | null;
  consents?: PortfolioConsent[];
}

export interface PortfolioTransaction {
  id: number;
  type: PortfolioTransactionType;
  /** YYYY-MM-DD */
  tradeDate: string;
  stockId: number | null;
  instrumentCode: string | null;
  instrumentName: string | null;
  quantity: number | null;
  price: number | null;
  amount: number;
  fee: number;
  tax: number;
  currency: string;
  memo: string | null;
}

export interface AddPortfolioTransactionRequest {
  type: PortfolioTransactionType;
  tradeDate: string;
  stockId?: number | null;
  instrumentCode?: string | null;
  instrumentName?: string | null;
  quantity?: number | null;
  price?: number | null;
  amount?: number | null;
  fee?: number | null;
  tax?: number | null;
  currency?: string | null;
  memo?: string | null;
}

export interface HoldingAccountShare {
  accountId: number | null;
  accountName: string;
  quantity: number;
}

export interface PortfolioHolding {
  stockId: number | null;
  instrumentCode: string | null;
  symbol: string | null;
  name: string | null;
  currency: string;
  quantity: number;
  avgCost: number | null;
  costBasis: number;
  /** 시세가 없는 상품(stocks에 없는 펀드·채권 등)은 null */
  currentPrice: number | null;
  marketValue: number | null;
  unrealizedPnl: number | null;
  unrealizedPnlRate: number | null;
  realizedPnl: number;
  dividendsNet: number;
  /** 같은 통화 안 평가금액 비중(%) */
  weight: number | null;
  accounts: HoldingAccountShare[];
  /** 가진 것보다 많이 판 기록이 있다 — 앞 구간 거래가 빠졌을 수 있다 */
  needsReview: boolean;
}

export interface PortfolioCurrencyTotal {
  currency: string;
  costBasis: number;
  marketValue: number;
  unrealizedPnl: number;
  unpricedCount: number;
}

export interface PortfolioHoldings {
  scope: HoldingsScope;
  holdings: PortfolioHolding[];
  totals: PortfolioCurrencyTotal[];
}

export interface HoldingEvent {
  id: number;
  stockId: number;
  symbol: string | null;
  name: string | null;
  eventType: string;
  title: string;
  description: string | null;
  eventTime: string;
  importanceScore: number;
  sourceType: string | null;
}

export interface HoldingEventFeed {
  events: HoldingEvent[];
  /** 다음 페이지는 cursor=nextCursor. 더 없으면 null */
  nextCursor: string | null;
  heldStockCount: number;
}

// ── CSV 가져오기 ──────────────────────────────────────────────────────
export type CsvField = "DATE" | "TYPE" | "SYMBOL" | "NAME" | "QUANTITY" | "PRICE" | "AMOUNT" | "FEE" | "TAX" | "CURRENCY" | "MEMO";

export interface ImportOptions {
  /** "MONTICKER_V1"(표준 템플릿) | "CUSTOM"(열 매핑) */
  format?: string;
  /** CUSTOM — 필드 → 파일의 헤더 이름 */
  columns?: Partial<Record<CsvField, string>>;
  /** 파일의 거래 구분 값 → 종류(예: "주식매수" → BUY) */
  typeValues?: Record<string, PortfolioTransactionType>;
  defaultCurrency?: string;
}

export type ImportRowStatus = "NEW" | "DUPLICATE" | "ERROR";

export interface ImportRowResult {
  line: number;
  status: ImportRowStatus;
  type: PortfolioTransactionType | null;
  tradeDate: string | null;
  symbol: string | null;
  name: string | null;
  /** stocks와 맞춘 종목. null이면 코드 그대로 들어간다(시세 없음) */
  stockId: number | null;
  quantity: number | null;
  price: number | null;
  amount: number | null;
  fee: number | null;
  tax: number | null;
  currency: string | null;
  message: string | null;
}

export interface ImportPreview {
  fileSha256: string;
  format: string;
  charset: string;
  rowCount: number;
  newCount: number;
  duplicateCount: number;
  errorCount: number;
  unmatchedCount: number;
  /** 오류·미매칭 행을 먼저, 최대 500행 */
  rows: ImportRowResult[];
}

export interface ImportResult {
  batchId: number;
  imported: number;
  duplicates: number;
  skipped: number;
}

export interface ImportBatch {
  id: number;
  format: string;
  rowCount: number;
  imported: number;
  duplicates: number;
  skipped: number;
  createdAt: string;
}
