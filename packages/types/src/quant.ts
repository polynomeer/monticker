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

export interface RuleDefinition {
  entryRules: RuleGroup;
  exitRules: RuleGroup;
  positionSizing: PositionSizing;
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
  subscribe_count: number;
  author_email: string;
  created_at: string;
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
  equityCurve: ForwardTestEquityPoint[];
  signals: ForwardTestSignal[];
}
