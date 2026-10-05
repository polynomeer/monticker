export interface Stock {
  id: number;
  symbol: string;
  name: string;
  market: "KOSPI" | "KOSDAQ" | "NASDAQ" | "NYSE";
  sector?: string;
  currency: string;
}

export interface PriceTick {
  stockId: number;
  symbol: string;
  price: number;
  changeRate: number;
  volume: number;
  timestamp: string;
}

/** GET /api/stocks/{id}/price */
export interface StockPriceResponse {
  stockId: number;
  symbol: string;
  price: number | null;
  volume: number | null;
  tradeTime: string | null;
  hasData: boolean;
  /** 전 거래일 종가(KRX만). 모르면 null */
  prevClose?: number | null;
  /** 전 거래일 대비 등락률(%). prevClose가 없으면 null */
  changeRate?: number | null;
}
