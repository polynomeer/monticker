"use client";

// 종목 화면(트레이딩·검색·비교)이 같이 쓰는 작은 조각들 — 시세 조회 훅, 이벤트 타입 표기, 숫자 포맷.
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";

/** /api/screener/quotes 응답 한 줄 — ScreenerItem 중 화면에서 쓰는 필드만 */
export interface Quote {
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  sector: string | null;
  price: number;
  changeRate: number;
  changeAmount: number;
  volume: number;
  amount: number;
  marketCap: number | null;
  per: number | null;
  isFundamentalsMocked: boolean;
}

/** 여러 종목의 현재 시세를 한 번에 — 없는 종목은 결과에서 빠진다. */
export function useQuotes(ids: number[], refetchMs = 15_000) {
  const key = Array.from(new Set(ids.filter((v) => Number.isFinite(v)))).sort((a, b) => a - b);
  const { data } = useQuery<Quote[]>({
    queryKey: ["screener", "quotes", "stock-terminal", key],
    queryFn: async () => {
      const r = await fetch(`/api/screener/quotes?ids=${key.join(",")}`);
      if (!r.ok) return [];
      const json = await r.json();
      return (json?.items ?? []) as Quote[];
    },
    enabled: key.length > 0,
    refetchInterval: refetchMs,
    staleTime: refetchMs,
  });
  const byId: Record<number, Quote> = {};
  for (const q of data ?? []) byId[q.stockId] = q;
  return byId;
}

// ── 이벤트 타입 ───────────────────────────────────────────────────────
export interface EventMeta {
  label: string;
  /** 차트·목록의 원형 마커 글자 */
  letter: string;
  color: string;
  /** 차트 이벤트 레이어 칩 키 */
  layer: EventLayer;
}

export type EventLayer = "disclosure" | "news" | "volume" | "price";

export const EVENT_META: Record<string, EventMeta> = {
  DISCLOSURE_PUBLISHED: { label: "공시", letter: "D", color: "#ffb86c", layer: "disclosure" },
  NEWS_PUBLISHED: { label: "뉴스", letter: "N", color: "#8be9fd", layer: "news" },
  VOLUME_SURGE: { label: "거래량", letter: "V", color: "#bd93f9", layer: "volume" },
  PRICE_SPIKE: { label: "급등", letter: "P", color: "#50fa7b", layer: "price" },
  PRICE_DROP: { label: "급락", letter: "P", color: "#ff79c6", layer: "price" },
  SECTOR_MOVE: { label: "섹터", letter: "S", color: "#c3c8e2", layer: "price" },
};

export function eventMeta(type: string): EventMeta {
  return EVENT_META[type] ?? { label: type.replace(/_/g, " "), letter: "E", color: "#c3c8e2", layer: "price" };
}

/** 시안의 원형 이벤트 마커(테두리 + 글자) */
export function EventDot({ type, size = 20 }: { type: string; size?: number }) {
  const m = eventMeta(type);
  return (
    <span
      aria-hidden
      className="num grid flex-none place-items-center rounded-full text-[10px] font-bold"
      style={{ width: size, height: size, border: `1.5px solid ${m.color}`, color: m.color }}
    >
      {m.letter}
    </span>
  );
}

// ── 숫자 ───────────────────────────────────────────────────────────────
/** 원화 큰 금액 — 1.2조 / 1,512억 / 3,400만 */
export function fmtKrwCompact(v: number | null | undefined) {
  if (v == null || !Number.isFinite(v)) return "—";
  const a = Math.abs(v);
  const s = v < 0 ? "-" : "";
  if (a >= 1e12) return `${s}${(a / 1e12).toFixed(a >= 1e14 ? 0 : 1)}조`;
  if (a >= 1e8) return `${s}${Math.round(a / 1e8).toLocaleString("ko-KR")}억`;
  if (a >= 1e4) return `${s}${Math.round(a / 1e4).toLocaleString("ko-KR")}만`;
  return `${s}${Math.round(a).toLocaleString("ko-KR")}`;
}

/** 주식 수량 — 2.1M주 */
export function fmtShares(v: number | null | undefined) {
  if (v == null || !Number.isFinite(v)) return "—";
  if (v >= 1e6) return `${(v / 1e6).toFixed(1)}M주`;
  if (v >= 1e3) return `${(v / 1e3).toFixed(1)}K주`;
  return `${v.toLocaleString("ko-KR")}주`;
}

export function fmtTime(iso: string | number) {
  const d = typeof iso === "number" ? new Date(iso * 1000) : new Date(iso);
  return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });
}

/** 하루 이상 지난 시각은 날짜로 */
export function fmtWhen(iso: string) {
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return fmtTime(iso);
  return d.toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" });
}

/** 비어 있음/로딩 상태 한 줄 */
export function Muted({ children, className }: { children: React.ReactNode; className?: string }) {
  return <p className={cn("m-0 py-6 text-center text-13 text-tm-muted", className)}>{children}</p>;
}

export function SkeletonRows({ n = 3, h = "h-12" }: { n?: number; h?: string }) {
  return (
    <div className="flex flex-col gap-2" aria-busy="true" aria-label="불러오는 중">
      {Array.from({ length: n }).map((_, i) => (
        <div key={i} className={cn("animate-pulse rounded-lg bg-tm-inner", h)} />
      ))}
    </div>
  );
}
