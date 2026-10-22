/** GET /api/watchlists — 그룹과 그룹 안 순서대로의 종목 */
export interface WatchlistItem {
  id: number;
  stockId: number;
  symbol: string;
  name: string;
  memo?: string | null;
  targetPrice?: number | null;
  sortOrder?: number;
}

export interface WatchlistGroup {
  id: number;
  name: string;
  sortOrder?: number;
  items: WatchlistItem[];
}

/** GET /api/alerts/rules 의 한 줄 */
export interface AlertRule {
  id: number;
  stockId: number | null;
  ruleType: string;
  conditionJson: string;
  isActive: boolean;
  createdAt: string;
}
