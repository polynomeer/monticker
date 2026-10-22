// 모의투자·지갑·리스크 응답(ADR-013·047·092)

/** GET /api/paper/portfolio */
export interface PaperPortfolio {
  cash: number;
  totalValue: number;
  totalPnl: number;
  totalPnlRate: number;
  holdings: PaperHolding[];
}

export interface PaperHolding {
  stockId: number;
  symbol: string;
  name: string;
  quantity: number;
  avgPrice: number;
  currentPrice: number;
  value: number;
  pnl: number;
  pnlRate: number;
  /** ADR-085 MANUAL · WATCH_RULE · CONDITIONAL · STRATEGY */
  entryOrigin?: string | null;
}

/** GET /api/risk/exposure */
export interface RiskExposure {
  totalAssets: number;
  availableCash: number;
  dailyPnl: number;
  dailyPnlPct: number;
  topConcentration: { stockId: number; symbol: string; valuePct: number } | null;
  estimatedVaR: number;
  activeOrderCount: number;
  hourlyOrderCount: number;
  limits: RiskLimits;
}

/** GET /api/risk/limits */
export interface RiskLimits {
  dailyLossLimitPct: number;
  concentrationLimitPct: number;
  varLimitPct: number;
  maxPositionCount: number;
  maxHourlyOrders: number;
  sectorConcentrationLimitPct: number | null;
  /** 모의투자 리스크 체크 활성화. 실거래 게이트는 이 값과 무관하게 항상 돈다(ADR-069) */
  isActive: boolean;
}
