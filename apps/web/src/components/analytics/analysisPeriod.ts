// /analytics 분석 기간 — 서버(AnalysisPeriod, ADR-097)와 같은 규칙을 화면에서 먼저 확인한다.
// 날짜는 한국 달력 날짜(YYYY-MM-DD) 문자열로만 다루고 UTC 자정 기준으로 계산해 브라우저 시간대와 무관하다.

import { kstToday } from "@/components/wallet/insights";

export type PeriodPreset = "3M" | "6M" | "1Y" | "2Y";
export type AnalysisPeriodSel = { kind: PeriodPreset } | { kind: "CUSTOM"; from: string; to: string };

export const PERIOD_PRESETS: { key: PeriodPreset; label: string }[] = [
  { key: "3M", label: "3개월" },
  { key: "6M", label: "6개월" },
  { key: "1Y", label: "1년" },
  { key: "2Y", label: "2년" },
];

export const MIN_CUSTOM_DAYS = 60;
export const MAX_CUSTOM_YEARS = 3;

const YMD = /^(\d{4})-(\d{2})-(\d{2})$/;

function parseYmd(s: string): number | null {
  const m = YMD.exec(s);
  if (!m) return null;
  const t = Date.UTC(+m[1], +m[2] - 1, +m[3]);
  const d = new Date(t);
  // 2026-02-30 같은 없는 날짜를 거른다
  if (d.getUTCFullYear() !== +m[1] || d.getUTCMonth() !== +m[2] - 1 || d.getUTCDate() !== +m[3]) return null;
  return t;
}

function toYmd(t: number) {
  const d = new Date(t);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** ymd에서 n년 전 같은 날짜(2/29 → 2/28) */
export function minusYears(ymd: string, years: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  const last = new Date(Date.UTC(y - years, m, 0)).getUTCDate();
  return toYmd(Date.UTC(y - years, m - 1, Math.min(d, last)));
}

export function minusDays(ymd: string, days: number): string {
  return toYmd((parseYmd(ymd) ?? 0) - days * 86_400_000);
}

/** 직접 지정 기간 검증. 문제가 없으면 null, 있으면 사용자에게 보일 문구. */
export function customPeriodError(from: string, to: string, today: string = kstToday()): string | null {
  const f = parseYmd(from);
  const t = parseYmd(to);
  if (f == null || t == null) return "시작일과 종료일을 모두 입력하세요";
  if (f >= t) return "시작일은 종료일보다 앞서야 합니다";
  if (to > today) return "종료일은 오늘 이후일 수 없습니다";
  if ((t - f) / 86_400_000 < MIN_CUSTOM_DAYS) return `분석 기간은 최소 ${MIN_CUSTOM_DAYS}일입니다`;
  if (from < minusYears(to, MAX_CUSTOM_YEARS)) return `분석 기간은 최대 ${MAX_CUSTOM_YEARS}년입니다`;
  return null;
}

/** API 쿼리 파라미터에 기간을 붙인다 */
export function appendPeriod(params: URLSearchParams, sel: AnalysisPeriodSel) {
  params.set("period", sel.kind);
  if (sel.kind === "CUSTOM") {
    params.set("from", sel.from);
    params.set("to", sel.to);
  }
}

export function periodLabel(sel: AnalysisPeriodSel): string {
  if (sel.kind === "CUSTOM") return `${sel.from} ~ ${sel.to}`;
  return PERIOD_PRESETS.find((p) => p.key === sel.kind)?.label ?? sel.kind;
}
