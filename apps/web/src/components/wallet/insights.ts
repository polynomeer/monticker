/**
 * ADR-091 — 지갑 지표(점수 카드 세부·날짜별 수익률·감정 분포 기간)와 체결 품질의 응답 모양과 표시 규칙.
 * 분모가 0이거나 값이 없으면 "—"(지어낸 숫자를 보여 주지 않는다). 행동 기록을 돌아보는 지표이며 투자 권유가 아니다.
 */

export interface Ratio { numerator: number; denominator: number; pct: number | null }
export interface WeeklyRatio { thisWeek: Ratio; lastWeek: Ratio; deltaPp: number | null }
export interface WeeklyScore { thisWeekAvg: number | null; lastWeekAvg: number | null; thisWeekDays: number; lastWeekDays: number; delta: number | null }
export interface ScoreDetails {
  weekStart: string;
  lastWeekStart: string;
  planAdherence: WeeklyRatio;
  stopLossAdherence: WeeklyRatio;
  behaviorScore: WeeklyScore;
}

export type DailyReturnStatus = "OK" | "NO_ACCOUNT" | "RESET" | "NO_PRICE" | "NO_EQUITY";
export interface DailyReturn {
  date: string;
  startEquity: number | null;
  endEquity: number | null;
  netFlow: number;
  pnl: number | null;
  returnPct: number | null;
  status: DailyReturnStatus;
}

export interface SlippageStat { avgBps: number | null; fillCount: number }
export interface ExecutionQuality {
  from: string;
  to: string;
  slippage: SlippageStat;
  slippageBySide: Record<string, SlippageStat>;
  slippageByOrderType: Record<string, SlippageStat>;
  excludedNoQuote: number;
  latency: { avgMs: number | null; p50Ms: number | null; p95Ms: number | null; orderCount: number; excludedNoSubmitTime: number };
}

/** 툴팁 문구 — ADR-091과 같은 정의 */
export const STOP_LOSS_DEFINITION =
  "손절 준수율: 이번 주 손실로 끝난 매도 중, 그 포지션에 손절(모의 조건부 주문)을 정해 둔 매도만 셉니다. " +
  "처음 정한 손절가 이상에서 나왔거나 그 손절이 직접 발동했으면 지킨 것으로 봅니다. 손절을 정하지 않은 매도는 비율에서 뺍니다. " +
  "기록을 돌아보기 위한 지표이며 투자 판단의 기준이 아닙니다.";
export const WEEK_DEFINITION = "주 = 월요일 00:00 ~ 일요일 24:00(한국 시간). 이번 주는 지금까지.";
export const DAILY_RETURN_DEFINITION =
  "날짜별 수익률 = 그날 손익 ÷ 그날 00:00 평가자산(현금 + 예약금 + 보유 평가액). 입출금은 손익에서 뺍니다. " +
  "초기화 이전 날짜와 시세가 없는 날은 —.";
export const SLIPPAGE_DEFINITION =
  "평균 슬리피지(최근 30일, 수량 가중): 매수는 주문 시점 최우선 매도호가, 매도는 최우선 매수호가와 체결가의 차이(bp). " +
  "+는 불리, −는 유리. 실시간 호가를 기록하지 못한 주문은 뺍니다.";
export const LATENCY_DEFINITION = "엔진 지연(최근 30일): 시장가 주문을 접수한 순간부터 첫 체결까지의 시간(중앙값). 지정가는 대기 시간이 섞여 뺍니다.";

/** 비율 → "67%". 분모 0이면 "—" */
export function ratioText(r: Ratio | null | undefined): string {
  if (!r || r.denominator === 0 || r.pct == null) return "—";
  return `${Math.round(r.pct)}%`;
}

/** 지난주 대비(%p) → "+12%p" / "−5%p" / "±0%p". 비교할 수 없으면 null(표시하지 않음) */
export function deltaPpText(d: number | null | undefined): string | null {
  if (d == null || Number.isNaN(d)) return null;
  const r = Math.round(d);
  if (r === 0) return "±0%p";
  return `${r > 0 ? "+" : "−"}${Math.abs(r)}%p`;
}

/** 점수 차 → "+3.5" / "−2" / "±0". 비교할 수 없으면 null */
export function scoreDeltaText(d: number | null | undefined): string | null {
  if (d == null || Number.isNaN(d)) return null;
  const r = Math.round(d * 10) / 10;
  if (r === 0) return "±0";
  return `${r > 0 ? "+" : "−"}${Math.abs(r)}`;
}

/** 날짜별 수익률 칩 → "+0.52%" · 계산 불가면 "—" */
export function dailyReturnText(d: DailyReturn | undefined): string {
  if (!d || d.status !== "OK" || d.returnPct == null) return "—";
  const v = d.returnPct;
  if (Math.abs(v) < 0.005) return "0.00%";
  return `${v > 0 ? "+" : ""}${v.toFixed(2)}%`;
}

/** bp → "+3.2bp" · 없으면 "—" */
export function bpsText(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  if (Math.abs(v) < 0.05) return "0.0bp";
  return `${v > 0 ? "+" : "−"}${Math.abs(v).toFixed(1)}bp`;
}

/** ms → "4.2ms" / "1.3s" · 없으면 "—" */
export function msText(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  if (v >= 1000) return `${(v / 1000).toFixed(1)}s`;
  return `${v < 10 ? v.toFixed(1) : Math.round(v)}ms`;
}

const pad = (n: number) => String(n).padStart(2, "0");
const toYmd = (t: number) => {
  const d = new Date(t);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
};

/**
 * 달력 날짜(YYYY-MM-DD, 한국 날짜)가 속한 주의 월요일·일요일. 날짜 산술만 하므로 브라우저 시간대와 무관하다
 * (UTC 자정으로 놓고 계산한다).
 */
export function kstWeekOf(ymd: string): { from: string; to: string } {
  const [y, m, d] = ymd.split("-").map(Number);
  const t = Date.UTC(y, m - 1, d);
  const dow = new Date(t).getUTCDay(); // 0=일
  const monday = t - ((dow + 6) % 7) * 86_400_000;
  return { from: toYmd(monday), to: toYmd(monday + 6 * 86_400_000) };
}

/** 지금 한국 날짜(YYYY-MM-DD) — 브라우저 시간대와 무관 */
export function kstToday(now: Date = new Date()): string {
  return toYmd(now.getTime() + 9 * 3_600_000);
}
