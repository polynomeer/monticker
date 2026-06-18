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
