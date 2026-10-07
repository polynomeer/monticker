/**
 * 거래 마커 — 체결을 봉 버킷에 맞추고, 같은 봉·같은 방향 체결을 하나로 묶는다.
 * 차트 라이브러리와 무관한 순수 함수라 어댑터가 바뀌어도 그대로 쓴다.
 */
import type { CandleData, ChartInterval, ChartTheme, TradeMarker } from "./types";

/** 한국은 일광절약시간이 없어 UTC+9 고정이다. */
const KST_OFFSET_SEC = 9 * 3600;

const BUCKET_SEC: Record<ChartInterval, number> = {
  "1m": 60,
  "3m": 180,
  "15m": 900,
  "1h": 3600,
  "1d": 86_400,
};

/**
 * 시각 t(epoch seconds)가 속한 봉 버킷 번호. 버킷 경계는 Asia/Seoul 기준이다 —
 * 일봉은 KST 자정, 분·시간봉은 KST 정각/정분(9시간이 모든 분봉 길이의 배수라 UTC 정렬과 같다).
 */
export function bucketOf(t: number, interval: ChartInterval): number {
  return Math.floor((t + KST_OFFSET_SEC) / BUCKET_SEC[interval]);
}

export interface TradeMarkerGroup {
  /** candles 배열 인덱스 */
  index: number;
  side: "BUY" | "SELL";
  /** 묶인 체결 건수 */
  count: number;
  /** 수량 합계 */
  quantity: number;
  /** 수량 가중 평균 체결가 */
  avgPrice: number;
  /** 묶인 체결(시각순) */
  trades: TradeMarker[];
}

/** keys(오름차순)에서 key 이하인 마지막 인덱스. 없으면 -1 */
function lastAtOrBefore(keys: number[], key: number): number {
  let lo = 0, hi = keys.length - 1, ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (keys[mid] <= key) { ans = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return ans;
}

/**
 * 체결을 봉에 맞춰 묶는다.
 * - 체결 시각이 속한 버킷의 봉에 붙인다. 그 버킷에 봉이 없으면(거래 정지·장 외 체결) 직전 봉에 붙인다.
 * - 첫 봉 버킷보다 이르거나 마지막 봉 버킷보다 늦은 체결은 차트 구간 밖이라 버린다.
 * - 수량·가격이 유효하지 않은 체결은 버린다(지어낸 값으로 채우지 않는다).
 * candles는 시각 오름차순이어야 한다.
 */
export function aggregateTradeMarkers(
  candles: Pick<CandleData, "time">[],
  trades: TradeMarker[],
  interval: ChartInterval = "1d",
): TradeMarkerGroup[] {
  if (candles.length === 0 || trades.length === 0) return [];
  const keys = candles.map((c) => bucketOf(c.time, interval));
  const first = keys[0];
  const last = keys[keys.length - 1];

  const groups = new Map<string, TradeMarkerGroup & { notional: number }>();
  for (const t of trades) {
    if (!Number.isFinite(t.time) || !Number.isFinite(t.price) || !(t.price > 0) || !(t.qty > 0)) continue;
    if (t.side !== "BUY" && t.side !== "SELL") continue;
    const key = bucketOf(t.time, interval);
    if (key < first || key > last) continue;
    const index = lastAtOrBefore(keys, key);
    if (index < 0) continue;
    const id = `${index}:${t.side}`;
    const g = groups.get(id);
    if (g) {
      g.count += 1;
      g.quantity += t.qty;
      g.notional += t.price * t.qty;
      g.trades.push(t);
    } else {
      groups.set(id, { index, side: t.side, count: 1, quantity: t.qty, notional: t.price * t.qty, avgPrice: 0, trades: [t] });
    }
  }

  return [...groups.values()]
    .map(({ notional, ...g }) => ({
      ...g,
      avgPrice: notional / g.quantity,
      trades: [...g.trades].sort((a, b) => a.time - b.time),
    }))
    .sort((a, b) => a.index - b.index || (a.side === b.side ? 0 : a.side === "BUY" ? -1 : 1));
}

function fmtWon(v: number): string {
  return v >= 100
    ? v.toLocaleString("ko-KR", { maximumFractionDigits: 0 })
    : v.toLocaleString("ko-KR", { maximumFractionDigits: 2 });
}

const KST_TIME = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

/** 툴팁 한 줄 요약 — "매수 3건 · 30주 · 평균 71,200원" (1건이면 "매수 · 10주 · 71,200원") */
export function describeTradeGroup(g: TradeMarkerGroup): string {
  const side = g.side === "BUY" ? "매수" : "매도";
  const head = g.count > 1 ? `${side} ${g.count}건` : side;
  const price = g.count > 1 ? `평균 ${fmtWon(g.avgPrice)}원` : `${fmtWon(g.avgPrice)}원`;
  const label = g.count === 1 && g.trades[0].label ? ` · ${g.trades[0].label}` : "";
  return `${head} · ${g.quantity.toLocaleString("ko-KR")}주 · ${price}${label}`;
}

/** 체결 시각(KST) 목록 — 툴팁 보조 줄. 많으면 앞의 n건만 */
export function tradeTimesKst(g: TradeMarkerGroup, n = 3): string {
  const shown = g.trades.slice(0, n).map((t) => KST_TIME.format(new Date(t.time * 1000)));
  return g.trades.length > n ? `${shown.join(", ")} 외 ${g.trades.length - n}건` : shown.join(", ");
}

/**
 * ECharts markPoint 데이터 항목으로 바꾼다 — 매수는 봉 아래 ▲(상승색), 매도는 봉 위 ▼(하락색).
 * 색은 사용자의 차트 색 테마(theme.upColor/downColor, CSS의 --mt-up/--mt-down과 같은 값)를 따른다.
 * x 좌표는 category 축의 인덱스로 준다 — 분봉은 날짜 라벨이 겹쳐 라벨로 찾으면 첫 봉에 붙는다.
 */
export function tradeMarkPoints(
  groups: TradeMarkerGroup[],
  candles: Pick<CandleData, "low" | "high">[],
  theme: Pick<ChartTheme, "upColor" | "downColor">,
) {
  return groups.map((g) => {
    const buy = g.side === "BUY";
    const c = candles[g.index];
    return {
      name: describeTradeGroup(g),
      coord: [g.index, buy ? c.low : c.high] as [number, number],
      value: `${buy ? "B" : "S"}${g.count > 1 ? g.count : ""}`,
      symbol: "pin",
      symbolRotate: buy ? 180 : 0,
      symbolOffset: [0, buy ? 16 : -16] as [number, number],
      symbolSize: g.count > 1 ? 26 : 22,
      itemStyle: { color: buy ? theme.upColor : theme.downColor },
      label: { color: "#1b1c24", fontSize: 9 },
      eventId: undefined,
    };
  });
}

/** "YYYY-MM-DD"(KST 날짜)를 그날 KST 자정의 epoch seconds로 — 백테스트 거래일을 일봉에 맞출 때 쓴다 */
export function kstDateToEpoch(date: string): number {
  return Math.floor(Date.parse(`${date}T00:00:00+09:00`) / 1000);
}
