/**
 * 홈 "내 종목 · 새 소식" 피드를 합친다(ADR-113 — 서버에 개인화 피드가 없어 화면에서 합친다).
 * 순수 함수라 노드 테스트로 확인한다. 타입은 필요한 필드만 받는다.
 */

export interface FeedEventInput {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  description?: string | null;
  eventTime: string;
}

export interface FeedSignalInput {
  id: number;
  ruleSetName: string;
  source: "MINE" | "SUBSCRIBED";
  stockId: number;
  symbol: string;
  stockName: string;
  direction: "BUY" | "SELL";
  signalTime: string;
}

export interface FeedStock {
  symbol: string;
  name: string;
  changeRate?: number;
}

export type FeedItem =
  | { kind: "event"; key: string; time: string; event: FeedEventInput; stock: FeedStock; relation: "held" | "watch" }
  | { kind: "signal"; key: string; time: string; signal: FeedSignalInput }
  | { kind: "risk"; key: string; time: string; usedPct: number };

export const PRICE_EVENTS = new Set(["PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE"]);

/** 일간 손실 한도를 이만큼 쓰면 경고 카드를 띄운다 */
export const RISK_WARN_PCT = 80;

interface Input {
  events: FeedEventInput[];
  signals: FeedSignalInput[];
  stocks: Map<number, FeedStock>;
  heldIds: Set<number>;
  /** 일간 손실 한도 사용률(%) — 손실이 없거나 모르면 null */
  riskUsedPct: number | null;
  now: Date;
}

export function buildFeed({ events, signals, stocks, heldIds, riskUsedPct, now }: Input): FeedItem[] {
  const items: FeedItem[] = [];
  for (const e of events) {
    const stock = stocks.get(e.stockId);
    if (!stock) continue;     // 이름을 모르는 종목은 보이지 않는다(관심·보유가 아닌 종목)
    items.push({ kind: "event", key: `e${e.id}`, time: e.eventTime, event: e, stock, relation: heldIds.has(e.stockId) ? "held" : "watch" });
  }
  for (const s of signals) items.push({ kind: "signal", key: `s${s.id}`, time: s.signalTime, signal: s });
  if (riskUsedPct != null && riskUsedPct >= RISK_WARN_PCT) {
    items.push({ kind: "risk", key: "risk", time: now.toISOString(), usedPct: riskUsedPct });
  }
  return items.sort((a, b) => b.time.localeCompare(a.time));
}

/** 일간 손익과 한도(%)로 한도 사용률. 이익이면 0 */
export function dailyLossUsedPct(dailyPnlPct: number, dailyLossLimitPct: number): number | null {
  if (!(dailyLossLimitPct > 0)) return null;
  if (dailyPnlPct >= 0) return 0;
  return Math.round((-dailyPnlPct / dailyLossLimitPct) * 100);
}

/** KST 날짜가 오늘인 항목 수 — 홈 부제 "새 소식 N건" */
export function countToday(items: FeedItem[], now: Date): number {
  const day = (d: Date) => d.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
  const today = day(now);
  return items.filter((i) => i.kind !== "risk" && day(new Date(i.time)) === today).length;
}

/** 오늘이면 HH:MM, 어제면 "어제", 그 전은 MM.DD (KST) */
export function feedTime(iso: string, now: Date): string {
  const d = new Date(iso);
  const day = (x: Date) => x.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
  if (day(d) === day(now)) {
    return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
  }
  const yesterday = new Date(now.getTime() - 86_400_000);
  if (day(d) === day(yesterday)) return "어제";
  const [, m, dd] = day(d).split("-");
  return `${m}.${dd}`;
}
