// Quant Lab — rule DSL, ruleset, backtest, and strategy market types.
// Mirrors backend/api/.../quant/{domain/QuantTypes.kt, application/RuleSetService.kt}.

export type RuleOperator = "AND" | "OR";

export type Comparator =
  | "GT" | "LT" | "GTE" | "LTE" | "EQ" | "BETWEEN"
  | "GOLDEN" | "DEAD" | "ABOVE_UPPER" | "BELOW_LOWER";

export interface RuleCondition {
  indicator: string;
  comparator: Comparator | string;
  params?: Record<string, number>;
  value?: number | [number, number];
}

export interface RuleGroup {
  operator: RuleOperator;
  conditions: RuleCondition[];
}

export interface PositionSizing {
  type: string;
  value: number;
}

/** ADR-079 — exitRules의 AND/OR와 무관하게 항상 먼저 보는 강제 청산. */
export interface HardExits {
  /** 진입 다음 거래일부터 센 보유 거래일 수(1~500) */
  maxHoldDays?: number;
  /** 진입 이후 최고 종가 대비 하락률 %(0 초과 50 이하) */
  trailingStopPct?: number;
}

export interface RuleDefinition {
  entryRules: RuleGroup;
  exitRules: RuleGroup;
  positionSizing: PositionSizing;
  hardExits?: HardExits;
}

export type RuleSetStatus = "DRAFT" | "BACKTESTED" | "RUNNING" | "ARCHIVED";

// GET/POST/PUT /api/quant/rulesets* — ruleDefinition/universeJson arrive as JSON strings,
// not parsed objects (RuleSetResponse.toResponse() serializes them with objectMapper).
export interface RuleSet {
  id: string;
  userId?: number;
  name: string;
  description: string | null;
  version: number;
  status: RuleSetStatus | string;
  ruleDefinition?: string;
  universeJson?: string;
  fingerprint?: string;
  versionCount?: number;
  createdAt: string;
  updatedAt: string;
  /** ADR-078 — 목록 조회(GET /api/quant/rulesets)에서만 채워진다. */
  performance?: StrategyPerformance | null;
}

/** 카드용 최신 백테스트 요약 — 지표와 다운샘플 자산 곡선(최대 48점). 수익률·MDD는 % 단위, MDD는 양수. */
export interface BacktestSummary {
  id: number;
  stockId: number;
  startDate: string;
  endDate: string;
  totalReturn: number | null;
  annualReturn: number | null;
  mdd: number | null;
  sharpe: number | null;
  tradeCount: number | null;
  reliabilityScore: string | null;
  curve: number[];
}

export interface ForwardSummary {
  status: "RUNNING" | "STOPPED" | string;
  startedAt: string;
  stoppedAt: string | null;
  matchRate: number | null;
  matchedSignals: number | null;
  comparedSignals: number | null;
}

export interface StrategyPerformance {
  backtest: BacktestSummary | null;
  forward: ForwardSummary | null;
}

export interface QuantTradeRecord {
  entryDate: string;
  exitDate: string;
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  pnl: number;
  pnlPct: number;
  exitReason: string;
}

export interface QuantEquityPoint {
  date: string;
  equity: number;
  drawdown: number;
}

// GET/POST /api/quant/rulesets/{id}/backtest
export interface QuantBacktestResult {
  id: number;
  ruleSetId: string;
  ruleSetVersion: number;
  stockId: number;
  startDate: string;
  endDate: string;
  initialCapital: number;
  finalCapital: number;
  totalReturn: number | null;
  annualReturn: number | null;
  mdd: number | null;
  winRate: number | null;
  profitFactor: number | null;
  tradeCount: number | null;
  avgHoldingDays: number | null;
  benchmarkReturn: number | null;
  excessReturn: number | null;
  /** ADR-079 — 연환산 샤프(무위험 3%). 이전 결과·계산 불가면 null */
  sharpe?: number | null;
  reliabilityScore: string | null;
  createdAt: string;
  trades: QuantTradeRecord[];
  equityCurve: QuantEquityPoint[];
}

// GET /api/quant/market — raw JdbcTemplate rows (snake_case), plus a `name` the
// controller backfills from the ruleset document since strategy_market has no
// name column of its own (ruleset_id points into Mongo, not a SQL-joinable FK).
export interface MarketStrategy {
  id: number;
  ruleset_id: string;
  name: string;
  description: string | null;
  price: number;
  subscribe_count: number;
  /** 작성자 닉네임. 이메일(로그인 ID)은 내보내지 않는다(보안 리뷰 2026-10). */
  author_nickname: string;
  created_at: string;
  // ADR-035 — 현재 로그인 사용자가 이 전략을 구독 중인지. 비로그인 조회 시 항상 false.
  isSubscribed: boolean;
  /** ADR-078 — 최신 백테스트 요약·포워드 일치율. 룰 정의는 포함하지 않는다. */
  performance?: StrategyPerformance | null;
}

// GET /api/quant/market/signals, /api/quant/market/{id}/signals — 구독 전략 신호 이력(ADR-035: 구독자·제작자만)
export interface MarketSignal {
  id: number;
  marketId: number;
  rulesetId: string;
  strategyName: string;
  direction: "BUY" | "SELL";
  stockId: number;
  /** 평가일 종가. 2026-10 이전 신호는 null */
  price: number | null;
  evalDate: string | null;
  signalTime: string;
}

export interface MarketSignalFeed {
  items: MarketSignal[];
  /** 이번 달(KST) 구독 전략 신호 수 */
  thisMonthCount: number;
}

// Quant Lab forward test (ADR-024) — /api/quant/rulesets/{id}/forward-test*
export type ForwardTestStatus = "RUNNING" | "STOPPED";

export interface ForwardTestEquityPoint {
  date: string;
  equity: number;
  drawdown: number;
}

export type SignalDirection = "BUY" | "SELL";

export interface ForwardTestSignal {
  direction: SignalDirection;
  signalTime: string;
  evalDate: string | null;
}

export interface ForwardTestResult {
  id: number;
  ruleSetId: string;
  stockId: number;
  status: ForwardTestStatus;
  initialCapital: number;
  cash: number;
  holdingQty: number;
  holdingEntryPrice: number | null;
  currentEquity: number;
  startedAt: string;
  stoppedAt: string | null;
  /**
   * ADR-078 — 포워드 일치율(0~1): 실제 포워드 신호와 같은 기간·같은 룰 재실행 신호의
   * (평가일, 방향) 자카드 지수. 비교할 신호가 아직 없거나 계산 전이면 null.
   */
  matchRate: number | null;
  matchedSignals: number | null;
  comparedSignals: number | null;
  matchEvaluatedAt: string | null;
  equityCurve: ForwardTestEquityPoint[];
  signals: ForwardTestSignal[];
}
