// 스크리너 조건 — 서버 ScreenerCriteria(ADR-072)와 같은 모양. 쿼리 파라미터와 저장 스크린이 이 타입 하나를 쓴다.

export type ScreenerEvent = "NEWS" | "DISCLOSURE" | "QUANT_SIGNAL" | "SENTIMENT";

export interface ScreenerCriteria {
  market: string;
  marketCapTier: string;
  sectors: string[];
  minChange: number | null;
  maxChange: number | null;
  minVolMult: number | null;
  events: ScreenerEvent[];
  sort: string;
  /** 시가총액 범위(원, 양 끝 포함). 걸면 시가총액이 없는 종목은 빠진다 */
  minCap: number | null;
  maxCap: number | null;
}

export const DEFAULT_CRITERIA: ScreenerCriteria = {
  market: "all",
  marketCapTier: "all",
  sectors: [],
  minChange: null,
  maxChange: null,
  minVolMult: null,
  events: [],
  sort: "amount",
  minCap: null,
  maxCap: null,
};

/** 시장 세그먼트 — 위 칸(전체/국내/해외)과 국내일 때만 보이는 아래 칸(국내 전체/코스피/코스닥). 서버 ScreenerCriteria.MARKETS와 같다. */
export const MARKET_GROUPS = [
  { value: "all",      label: "전체" },
  { value: "domestic", label: "국내" },
  { value: "overseas", label: "해외" },
] as const;
export type MarketGroup = (typeof MARKET_GROUPS)[number]["value"];

export const DOMESTIC_SEGMENTS = [
  { value: "domestic", label: "국내 전체" },
  { value: "kospi",    label: "코스피" },
  { value: "kosdaq",   label: "코스닥" },
] as const;

/** market 값이 속한 위 칸. kospi/kosdaq은 국내 */
export function marketGroup(market: string): MarketGroup {
  if (market === "kospi" || market === "kosdaq" || market === "domestic") return "domestic";
  return market === "overseas" ? "overseas" : "all";
}

export const EVENT_OPTIONS: { key: ScreenerEvent; label: string; sub?: string }[] = [
  { key: "NEWS", label: "뉴스 동반" },
  { key: "DISCLOSURE", label: "공시 동반" },
  { key: "QUANT_SIGNAL", label: "퀀트 시그널 발생", sub: "내 전략·구독 전략의 오늘 신호" },
  { key: "SENTIMENT", label: "감성 급변" },
];

/**
 * 등락률 슬라이더 눈금(%). 양 끝은 "제한 없음" — 왼쪽 끝이면 하한 없음, 오른쪽 끝이면 상한 없음.
 * 눈금을 고정해 두면 저장 스크린을 다시 열었을 때 같은 위치로 돌아온다.
 */
export const CHANGE_STOPS = [-30, -10, -5, -3, -1, 0, 1, 3, 5, 10, 30] as const;
/** 거래량 배수 하한 눈금. 0번(=1.0×도 아닌 "제한 없음")은 null */
export const VOL_MULT_STOPS = [null, 1, 1.5, 2, 3, 5, 10] as const;

/** 등락률 [하한, 상한] → 슬라이더 위치 [a, b] */
export function changeToStops(min: number | null, max: number | null): [number, number] {
  const last = CHANGE_STOPS.length - 1;
  const idx = (v: number | null, fallback: number) => {
    if (v == null) return fallback;
    const i = CHANGE_STOPS.findIndex((s) => s >= v);
    return i < 0 ? last : i;
  };
  const a = idx(min, 0);
  const b = max == null ? last : Math.max(a, idx(max, last));
  return [a, b];
}

/** 슬라이더 위치 → 등락률 [하한, 상한]. 끝 눈금은 null(제한 없음). */
export function stopsToChange(a: number, b: number): { minChange: number | null; maxChange: number | null } {
  const last = CHANGE_STOPS.length - 1;
  const lo = Math.min(a, b);
  const hi = Math.max(a, b);
  return {
    minChange: lo <= 0 ? null : CHANGE_STOPS[lo],
    maxChange: hi >= last ? null : CHANGE_STOPS[hi],
  };
}

export function volMultToStop(v: number | null) {
  if (v == null) return 0;
  const i = VOL_MULT_STOPS.findIndex((s) => s != null && s >= v);
  return i < 0 ? VOL_MULT_STOPS.length - 1 : i;
}

export function changeRangeLabel(min: number | null, max: number | null) {
  const f = (v: number) => `${v > 0 ? "+" : ""}${v}%`;
  if (min == null && max == null) return "전체";
  if (min == null) return `~ ${f(max!)}`;
  if (max == null) return `${f(min)} ~`;
  return `${f(min)} ~ ${f(max)}`;
}

export function volMultLabel(v: number | null) {
  return v == null ? "전체" : `${v}× 이상`;
}

/** /api/screener 쿼리 문자열(기본값은 생략해 캐시 키·URL을 짧게) */
export function toQuery(tab: string, c: ScreenerCriteria, limit: number, offset: number): string {
  const p = new URLSearchParams({ tab, market: c.market, sort: c.sort, limit: String(limit), offset: String(offset), marketCapTier: c.marketCapTier });
  if (c.sectors.length) p.set("sectors", c.sectors.join(","));
  if (c.minChange != null) p.set("minChange", String(c.minChange));
  if (c.maxChange != null) p.set("maxChange", String(c.maxChange));
  if (c.minVolMult != null) p.set("minVolMult", String(c.minVolMult));
  if (c.events.length) p.set("events", c.events.join(","));
  if (c.minCap != null) p.set("minCap", String(c.minCap));
  if (c.maxCap != null) p.set("maxCap", String(c.maxCap));
  return p.toString();
}

/** 조건 비교(저장 스크린과 지금 필터가 같은지) — 목록 순서는 무시 */
export function sameCriteria(a: ScreenerCriteria, b: ScreenerCriteria) {
  const norm = (c: ScreenerCriteria) => JSON.stringify({ ...c, sectors: [...c.sectors].sort(), events: [...c.events].sort() });
  return norm(a) === norm(b);
}

/** 서버가 돌려준 저장 조건을 화면 타입으로(빠진 필드는 기본값) */
export function fromServer(c: Partial<ScreenerCriteria> | null | undefined): ScreenerCriteria {
  return {
    ...DEFAULT_CRITERIA,
    ...(c ?? {}),
    sectors: c?.sectors ?? [],
    events: (c?.events ?? []) as ScreenerEvent[],
    minChange: c?.minChange ?? null,
    maxChange: c?.maxChange ?? null,
    minVolMult: c?.minVolMult ?? null,
    minCap: c?.minCap ?? null,
    maxCap: c?.maxCap ?? null,
  };
}

// ── 시가총액 범위 — 화면은 억원 단위로 입력받고 서버에는 원으로 보낸다 ─────────────
export const EOK = 100_000_000;
/** 서버 ScreenerCriteria.MARKET_CAP_CEIL(1경 원)을 억원으로 */
export const MARKET_CAP_CEIL_EOK = 100_000_000;

/**
 * 억원 입력 → 원. 빈 값은 null(제한 없음). 음수·숫자 아님·상한 초과는 "invalid".
 * 소수 억원(예: 0.5 = 5천만 원)은 원 단위로 반올림한다.
 */
export function parseCapEok(text: string): number | null | "invalid" {
  const t = text.trim().replace(/,/g, "");
  if (t === "") return null;
  if (!/^\d+(\.\d+)?$/.test(t)) return "invalid";
  const v = Number(t);
  if (!Number.isFinite(v) || v < 0 || v > MARKET_CAP_CEIL_EOK) return "invalid";
  return Math.round(v * EOK);
}

/** 원 → 억원 입력값(빈 값은 ""). */
export function capToEokText(won: number | null): string {
  return won == null ? "" : String(won / EOK);
}

/** 범위 검증 — 문제가 있으면 메시지, 없으면 null */
export function capRangeError(min: number | null | "invalid", max: number | null | "invalid"): string | null {
  if (min === "invalid" || max === "invalid") return "0 이상 1억(억원) 이하의 숫자를 입력하세요.";
  if (min != null && max != null && min > max) return "최소가 최대보다 큽니다.";
  return null;
}
