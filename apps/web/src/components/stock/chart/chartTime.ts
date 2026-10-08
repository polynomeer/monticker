/**
 * 시각 ↔ 봉 위치 변환 — 이벤트·시그널 마커와 드로잉을 봉 버킷(Asia/Seoul)에 맞춘다.
 * 차트 라이브러리와 무관한 순수 함수다. category x축은 숫자를 봉 인덱스로 받으므로,
 * 날짜 문자열(분봉에서는 하루 내내 같은 값) 대신 인덱스로 위치를 넘긴다.
 */
import type { ChartInterval } from "./types";
import { bucketOf } from "./tradeMarkers";

const KST_OFFSET_SEC = 9 * 3600;

const BUCKET_SEC: Record<ChartInterval, number> = {
  "1m": 60,
  "3m": 180,
  "15m": 900,
  "1h": 3600,
  "1d": 86_400,
};

const INTRADAY = new Set<ChartInterval>(["1m", "3m", "15m", "1h"]);

export function isIntraday(interval: ChartInterval): boolean {
  return INTRADAY.has(interval);
}

/**
 * 화면의 봉 간격 값("1m"…"1d", "1w", "1M" 등)을 버킷 계산용 간격으로.
 * 주·월·연 단위 봉은 "1d" 버킷으로 계산해도 "시각 이하인 마지막 봉"에 붙으므로 맞는 봉을 고른다.
 */
export function toChartInterval(value: string | null | undefined): ChartInterval {
  return value === "1m" || value === "3m" || value === "15m" || value === "1h" ? value : "1d";
}

/** interval을 받지 못했을 때 봉 시각 간격(중앙값)으로 추정한다. */
export function inferInterval(times: number[]): ChartInterval {
  if (times.length < 2) return "1d";
  const diffs: number[] = [];
  for (let i = 1; i < times.length; i++) diffs.push(times[i] - times[i - 1]);
  diffs.sort((a, b) => a - b);
  const median = diffs[diffs.length >> 1];
  if (median <= 60) return "1m";
  if (median <= 180) return "3m";
  if (median <= 900) return "15m";
  if (median <= 3600) return "1h";
  return "1d";
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
 * 시각 t가 속한 봉의 인덱스(마커용). 그 버킷에 봉이 없으면 직전 봉, 첫 봉 버킷보다 이르거나
 * 마지막 봉 버킷보다 늦으면 차트 구간 밖이라 -1. times는 오름차순.
 */
export function candleIndexAt(times: number[], t: number, interval: ChartInterval): number {
  if (times.length === 0 || !Number.isFinite(t)) return -1;
  const key = bucketOf(t, interval);
  if (key < bucketOf(times[0], interval) || key > bucketOf(times[times.length - 1], interval)) return -1;
  return lastAtOrBefore(times.map((x) => bucketOf(x, interval)), key);
}

function avgStep(times: number[], interval: ChartInterval): number {
  const n = times.length;
  const s = n > 1 ? (times[n - 1] - times[0]) / (n - 1) : 0;
  return s > 0 ? s : BUCKET_SEC[interval];
}

/**
 * 드로잉 점의 x 위치(소수 인덱스). 봉 안에서는 다음 봉까지의 비율만큼 오른쪽으로,
 * 차트 구간 밖은 평균 봉 간격으로 외삽한다 — 줌·간격 변경 뒤에도 선의 기울기가 유지되도록.
 */
export function fractionalIndexAt(times: number[], t: number, interval: ChartInterval): number {
  const n = times.length;
  if (n === 0 || !Number.isFinite(t)) return 0;
  const step = avgStep(times, interval);
  const key = bucketOf(t, interval);
  if (key < bucketOf(times[0], interval)) return Math.min(-0.5, (t - times[0]) / step);
  if (key > bucketOf(times[n - 1], interval)) return Math.max(n - 0.5, n - 1 + (t - times[n - 1]) / step);
  const i = Math.max(0, lastAtOrBefore(times.map((x) => bucketOf(x, interval)), key));
  const width = i < n - 1 ? times[i + 1] - times[i] : step;
  const frac = width > 0 ? (t - times[i]) / width : 0;
  return i + Math.max(0, Math.min(0.999, frac));
}

/** fractionalIndexAt의 역 — 소수 인덱스를 시각(epoch seconds, 정수)으로. 구간 밖은 외삽 */
export function timeAtIndex(times: number[], index: number, interval: ChartInterval = "1d"): number {
  const n = times.length;
  if (n === 0) return 0;
  const step = avgStep(times, interval);
  if (index <= 0) return Math.round(times[0] + index * step);
  if (index >= n - 1) return Math.round(times[n - 1] + (index - (n - 1)) * step);
  const i = Math.floor(index);
  return Math.round(times[i] + (index - i) * (times[i + 1] - times[i]));
}

const pad = (v: number) => String(v).padStart(2, "0");

/** 축·툴팁 라벨 — Asia/Seoul 기준. 일봉 이상은 "YYYY-MM-DD", 분·시간봉은 "MM-DD HH:mm" */
export function fmtCandleLabel(t: number, interval: ChartInterval): string {
  const d = new Date((t + KST_OFFSET_SEC) * 1000);
  const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  if (!isIntraday(interval)) return date;
  return `${date.slice(5)} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}
