// 시장 데이터 응답 — 웹은 아직 각 화면 파일에 같은 모양을 두고 있다(옮기기 전). 모바일이 여기서 쓴다.

/** GET /api/market/indices (ADR-071). isMocked면 개발용 모의 값 — 화면에 "모의"로 표시한다 */
export interface MarketIndex {
  code: string;
  name: string;
  value: number;
  prevClose: number | null;
  change: number | null;
  changeRate: number | null;
  asOf: string;
  source: string;
  isMocked: boolean;
  closes: number[];
}

export type MarketPhase = "OPEN" | "PRE" | "POST" | "CLOSED";

/** GET /api/market/status (ADR-086) */
export interface MarketStatus {
  market: string;
  status: MarketPhase;
  date: string;
  isTradingDay: boolean;
  holidayName: string | null;
  openAt: string | null;
  closeAt: string | null;
  nextOpen: string;
  nextClose: string;
  calendarCovered: boolean;
  calendarCoverageUntil: string | null;
}

/** GET /api/market/intraday?ids= — 장중 1분봉 종가(스파크라인) */
export interface IntradaySeries {
  stockId: number;
  closes: number[];
  lastTime: string | null;
}

/** GET /api/screener · /api/screener/quotes?ids= 의 한 줄 */
export interface ScreenerQuote {
  rank?: number;
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  sector?: string | null;
  price: number;
  changeRate: number;
  changeAmount: number;
  volume: number;
  amount: number;
  /** 최신 일봉 거래량 ÷ 직전 20거래일 평균(ADR-072) */
  volumeMultiple?: number | null;
  /** 오늘(KST) 생긴 이벤트 유형, 중요도 높은 순 */
  todayEvents?: string[];
}

/** GET /api/stocks/search 의 한 줄 */
export interface StockSearchItem {
  id: number;
  symbol: string;
  name: string;
  market: string;
}
