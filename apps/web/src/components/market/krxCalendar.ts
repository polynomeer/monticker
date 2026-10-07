"use client";

// ADR-086 — KRX 거래일 캘린더(서버 /api/market/status · /api/market/calendar)와 그걸 못 받았을 때의 클라이언트 대체 계산.
// 날짜는 모두 KST "YYYY-MM-DD" 문자열로 다룬다(브라우저 시간대와 무관하게).
import { useQuery } from "@tanstack/react-query";

export type MarketPhase = "OPEN" | "PRE" | "POST" | "CLOSED";

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

export interface MarketHoliday { date: string; name: string; }

export interface MarketCalendar {
  market: string;
  from: string;
  to: string;
  holidays: MarketHoliday[];
  businessDays: string[];
  /** 휴장일 데이터가 없는 해 — 이 해의 영업일은 주말만 뺀 값 */
  uncoveredYears: number[];
  calendarCoverageUntil: string | null;
}

const KST = "Asia/Seoul";

/** KST 기준 날짜 "YYYY-MM-DD" */
export function kstYmd(d: Date = new Date()): string {
  return d.toLocaleDateString("en-CA", { timeZone: KST });
}

/** KST 기준 시·분 */
function kstClock(d: Date): { h: number; m: number } {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: KST, hour: "2-digit", minute: "2-digit", hour12: false }).formatToParts(d);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? "0");
  return { h: get("hour") % 24, m: get("minute") };
}

/** "YYYY-MM-DD"에 n일 더하기(달력 계산만, 시간대 영향 없음) */
export function addDays(ymd: string, n: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return t.toISOString().slice(0, 10);
}

/** 0=일 … 6=토 */
export function weekdayOf(ymd: string): number {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}

/** 주말이 아니고 휴장일 목록에 없으면 영업일. 휴장일 목록이 없으면(서버 실패) 주말만 뺀다. */
export function isBusinessDay(ymd: string, holidays: ReadonlySet<string> = new Set()): boolean {
  const wd = weekdayOf(ymd);
  return wd !== 0 && wd !== 6 && !holidays.has(ymd);
}

/** from(포함)부터 영업일 n개 */
export function nextBusinessDays(n: number, from: string, holidays?: ReadonlySet<string>): string[] {
  const out: string[] = [];
  let d = from;
  for (let guard = 0; out.length < n && guard < 400; guard++) {
    if (isBusinessDay(d, holidays)) out.push(d);
    d = addDays(d, 1);
  }
  return out;
}

/** anchor(포함)부터 거슬러 영업일 n개, 오래된 순 */
export function previousBusinessDays(n: number, anchor: string, holidays?: ReadonlySet<string>): string[] {
  const out: string[] = [];
  let d = anchor;
  for (let guard = 0; out.length < n && guard < 400; guard++) {
    if (isBusinessDay(d, holidays)) out.unshift(d);
    d = addDays(d, -1);
  }
  return out;
}

/** 영업일 delta개만큼 이동 */
export function shiftBusinessDays(anchor: string, delta: number, holidays?: ReadonlySet<string>): string {
  let d = anchor;
  let left = Math.abs(delta);
  for (let guard = 0; left > 0 && guard < 400; guard++) {
    d = addDays(d, Math.sign(delta));
    if (isBusinessDay(d, holidays)) left--;
  }
  return d;
}

/** 이번 주 월요일·일요일(KST) */
export function kstWeek(today: string): { from: string; to: string } {
  const wd = weekdayOf(today);
  const from = addDays(today, wd === 0 ? -6 : 1 - wd);
  return { from, to: addDays(from, 6) };
}

export interface SessionLabel { text: string; open: boolean; phase: MarketPhase; holidayName: string | null; covered: boolean; }

const hhmm = ({ h, m }: { h: number; m: number }) => `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;

function phaseAt(mins: number, openMins: number): MarketPhase {
  if (mins < 8 * 60 + 30) return "CLOSED";
  if (mins < openMins) return "PRE";
  if (mins < 15 * 60 + 30) return "OPEN";
  if (mins < 18 * 60) return "POST";
  return "CLOSED";
}

function labelOf(phase: MarketPhase, clock: string, holidayName: string | null): string {
  if (holidayName) return `휴장 · ${holidayName}`;
  switch (phase) {
    case "OPEN": return `정규장 · ${clock}`;
    case "PRE": return `장 시작 전 · ${clock}`;
    default: return `장 마감 · ${clock}`;
  }
}

/**
 * 상단 장 상태 문구. 오늘의 사실(거래일인지, 휴장일 이름, 개장 시각)은 서버 값을 쓰고, 시각에 따른 단계는 지금 시각으로 다시 계산한다
 * (상태를 1분마다 받아도 그 사이 09:00·15:30 경계를 넘기 때문). 서버 값이 없거나 날짜가 지났으면 평일 09:00–15:30 규칙으로 대신한다.
 */
export function sessionLabel(status: MarketStatus | null | undefined, now: Date = new Date()): SessionLabel {
  const clock = kstClock(now);
  const mins = clock.h * 60 + clock.m;
  const today = kstYmd(now);

  if (status && status.date === today) {
    if (!status.isTradingDay) {
      return { text: labelOf("CLOSED", hhmm(clock), status.holidayName), open: false, phase: "CLOSED", holidayName: status.holidayName, covered: status.calendarCovered };
    }
    const open = status.openAt ? kstClock(new Date(status.openAt)) : { h: 9, m: 0 };
    const phase = phaseAt(mins, open.h * 60 + open.m);
    const suffix = status.calendarCovered ? "" : " · 휴장일 미확인";
    return { text: labelOf(phase, hhmm(clock), null) + suffix, open: phase === "OPEN", phase, holidayName: null, covered: status.calendarCovered };
  }

  // 대체 계산 — 휴장일을 모른다
  const phase = isBusinessDay(today) ? phaseAt(mins, 9 * 60) : "CLOSED";
  return { text: labelOf(phase, hhmm(clock), null), open: phase === "OPEN", phase, holidayName: null, covered: false };
}

export function useMarketStatus() {
  return useQuery<MarketStatus | null>({
    queryKey: ["market", "status"],
    queryFn: async () => {
      const r = await fetch("/api/market/status");
      if (!r.ok) throw new Error("장 상태 조회 실패");
      return r.json();
    },
    refetchInterval: 60_000,
    staleTime: 30_000,
    retry: 1,
  });
}

/** 기간의 KRX 휴장일·영업일. 실패하면 undefined — 호출부는 주말만 빼는 계산으로 대신한다. */
export function useMarketCalendar(from: string, to: string) {
  return useQuery<MarketCalendar>({
    queryKey: ["market", "calendar", from, to],
    queryFn: async () => {
      const r = await fetch(`/api/market/calendar?from=${from}&to=${to}`);
      if (!r.ok) throw new Error("휴장일 조회 실패");
      return r.json();
    },
    staleTime: 60 * 60_000,
    retry: 1,
  });
}

export function holidaySet(cal: MarketCalendar | undefined): Set<string> {
  return new Set((cal?.holidays ?? []).map((h) => h.date));
}
