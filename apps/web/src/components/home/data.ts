"use client";

// 홈·관심종목 화면이 같이 쓰는 데이터 훅. queryKey를 기존 위젯(TopMovers/RecentEvents/WatchlistTicker,
// useWatchlistIds)과 똑같이 맞춰 두어 한 화면에 여러 패널이 떠도 react-query가 요청을 하나로 합친다.
import { useEffect, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { fetchCandles, stockKeys } from "@/hooks/useStockChart";
import type { ScreenerItem } from "@/hooks/useScreener";

export interface RecentEvent {
  id: number;
  stockId: number;
  eventType: string;
  title: string;
  description?: string | null;
  importanceScore: number;
  eventTime: string;
  /** 이벤트 구간 변동률(%) — /api/events/recent만 채운다. 1분봉이 모자라면 null */
  windowChangePct?: number | null;
  /** 이벤트 직후 5분 ÷ 직전 60분 평균 거래량 */
  volumeMultiple?: number | null;
}

export interface WatchlistItem { id: number; stockId: number; symbol: string; name: string; memo?: string | null; }
export interface WatchlistGroup { id: number; name: string; sortOrder?: number; items: WatchlistItem[]; }

/** 서버 EventType → 시안의 이벤트 라벨(EVENT_COLOR 키) */
export const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등",
  PRICE_DROP: "급락",
  VOLUME_SURGE: "거래량",
  NEWS_PUBLISHED: "뉴스",
  DISCLOSURE_PUBLISHED: "공시",
  SENTIMENT_CHANGE: "감성",
  SECTOR_MOVE: "섹터",
  USER_MEMO: "메모",
  SIMULATION_TRADE: "모의매매",
};

export function eventLabel(type: string) {
  return EVENT_LABEL[type] ?? type;
}

/** 로그인 여부 — 토큰은 클라이언트에만 있으므로 마운트 후에 읽는다(하이드레이션 불일치 방지). */
export function useIsLoggedIn() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);
  return isLoggedIn;
}

/** 최근 이벤트 50건 — 10초 폴링 */
export function useRecentEvents() {
  return useQuery<RecentEvent[]>({
    queryKey: ["events", "recent", "home"],
    queryFn: async () => {
      const r = await fetch("/api/events/recent?limit=50");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 10_000,
    staleTime: 10_000,
  });
}

/** 관심종목 그룹 — useWatchlistIds/WatchlistTicker와 같은 키 */
export function useWatchlistGroups(enabled: boolean) {
  return useQuery<WatchlistGroup[]>({
    queryKey: ["watchlist", "groups"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled,
    staleTime: 15_000,
  });
}

/** 종목 ID 목록의 현재 시세(이름·심볼 포함). 서버가 50개까지만 받는다. */
export function useQuotes(stockIds: number[], scope: string, refetchInterval?: number) {
  const ids = stockIds.slice(0, 50);
  const { data = [] } = useQuery<ScreenerItem[]>({
    queryKey: ["screener", "quotes-list", scope, ids],
    queryFn: async () => {
      const r = await fetch(`/api/screener/quotes?ids=${ids.join(",")}`);
      if (!r.ok) return [];
      const body = await r.json();
      return body.items ?? [];
    },
    enabled: ids.length > 0,
    refetchInterval,
    staleTime: refetchInterval ?? 30_000,
  });
  return new Map(data.map((q) => [q.stockId, q]));
}

/** 여러 종목의 일봉 종가(최근 30일) — 스파크라인용. useStockChart와 같은 캐시 키. */
export function useDailyCloses(stockIds: number[]) {
  const results = useQueries({
    queries: stockIds.map((id) => ({
      queryKey: stockKeys.candles(id, "1d"),
      queryFn: () => fetchCandles(id, "1d"),
      staleTime: 60_000,
    })),
  });
  const out = new Map<number, number[]>();
  stockIds.forEach((id, i) => out.set(id, (results[i]?.data ?? []).map((c) => c.close)));
  return out;
}

/** GET /api/market/indices 응답(ADR-071). isMocked=true면 개발용 모의 값이다 — 화면에 "모의"로 표시한다. */
export interface MarketIndex {
  code: string;
  name: string;
  value: number;
  prevClose: number | null;
  change: number | null;
  changeRate: number | null;
  asOf: string;
  source: string;
  isMocked: boolean;
  closes: number[];
}

/** 지수·환율 — 30초 폴링(worker 수집 주기와 같다). 홈 카드와 상단 스탯이 같은 키를 공유한다. */
export function useMarketIndices() {
  return useQuery<MarketIndex[]>({
    queryKey: ["market", "indices"],
    queryFn: async () => {
      const r = await fetch("/api/market/indices");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 30_000,
    staleTime: 30_000,
  });
}

/** 거래량 배수 표시 — 2.4× */
export function fmtMult(v: number | null | undefined, digits = 1) {
  if (v == null || !Number.isFinite(v)) return "—";
  return `${v.toFixed(digits)}×`;
}

/** 지수·환율 값 — 소수 2자리 */
export function fmtIndexValue(v: number | null | undefined) {
  if (v == null || Number.isNaN(v)) return "—";
  return v.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** KST 기준 시각 문자열 —오늘이면 HH:MM, 이전 날짜면 MM.DD HH:MM(시각만 보이면 며칠 전 이벤트가 오늘 것처럼 읽힌다) */
export function kstTime(iso: string, now: Date = new Date()) {
  const d = new Date(iso);
  const day = (x: Date) => x.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
  const time = d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
  if (day(d) === day(now)) return time;
  const [, m, dd] = day(d).split("-");
  return `${m}.${dd} ${time}`;
}

/**
 * 국내 정규장 상태(평일 09:00–15:30 KST). 공휴일 캘린더가 없어 휴장일은 구분하지 못한다.
 * 현재 시각에 의존하므로 마운트 후에만 계산한다.
 */
export function useKrxSession() {
  const [label, setLabel] = useState<{ text: string; open: boolean } | null>(null);
  useEffect(() => {
    const tick = () => {
      const parts = new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Seoul", weekday: "short", hour: "2-digit", minute: "2-digit", hour12: false }).formatToParts(new Date());
      const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
      const wd = get("weekday");
      const h = Number(get("hour")) % 24;
      const m = Number(get("minute"));
      const mins = h * 60 + m;
      const hhmm = `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
      const weekday = !["Sat", "Sun"].includes(wd);
      if (weekday && mins >= 540 && mins < 930) setLabel({ text: `정규장 · ${hhmm}`, open: true });
      else if (weekday && mins >= 480 && mins < 540) setLabel({ text: `장 시작 전 · ${hhmm}`, open: false });
      else setLabel({ text: `장 마감 · ${hhmm}`, open: false });
    };
    tick();
    const id = setInterval(tick, 30_000);
    return () => clearInterval(id);
  }, []);
  return label;
}
