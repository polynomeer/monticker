"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Icon, IconBtn, Panel, fmtNum } from "@/components/terminal";
import StockChart from "./chart/StockChart";
import type { Drawing, DrawingTool, IndicatorKey, OrderLine } from "./chart/types";
import IndicatorChart from "./IndicatorChart";
import VolumeChart from "./VolumeChart";
import SummaryPanel from "./SummaryPanel";
import NewsPanel from "./NewsPanel";
import InvestorFlowPanel from "./InvestorFlowPanel";
import StockScoreCard from "./StockScoreCard";
import StockCommentPanel from "./StockCommentPanel";
import { useStockChart } from "@/hooks/useStockChart";
import { useVwap } from "@/hooks/useVwap";
import { useRecentlyViewedStocks } from "@/hooks/useRecentlyViewedStocks";
import { useAuth } from "@/hooks/useAuth";
import { authFetch } from "@/services/api";
import { cn } from "@/lib/utils";
import { EVENT_META, type EventLayer, useQuotes } from "./parts";

export type ChartTab = "chart" | "summary" | "news" | "flow" | "community" | "watchlist";

export const CHART_TABS: { key: ChartTab; label: string }[] = [
  { key: "chart", label: "차트" },
  { key: "summary", label: "왜 움직였나" },
  { key: "news", label: "뉴스" },
  { key: "flow", label: "투자자 동향" },
  { key: "community", label: "토론" },
  { key: "watchlist", label: "관심종목" },
];

/** 시안의 타임프레임 7개 + 기존에 있던 장기 범위 2개. 3분·15분·1시간 봉은 서버에 아직 없다. */
const INTERVALS: { label: string; value: string | null }[] = [
  { label: "1분", value: "1m" },
  { label: "3분", value: null },
  { label: "15분", value: null },
  { label: "1시간", value: null },
  { label: "일", value: "1d" },
  { label: "주", value: "1w" },
  { label: "월", value: "1M" },
  { label: "1년", value: "1Y" },
  { label: "3년", value: "3M" },
];

const INDICATORS: { key: IndicatorKey; label: string }[] = [
  { key: "MA5", label: "MA5" },
  { key: "MA20", label: "MA20" },
  { key: "MA60", label: "MA60" },
  { key: "BOLL", label: "볼린저밴드" },
];

type SubPane = "none" | "volume" | "rsi" | "macd";

const LAYERS: { key: EventLayer | "quant" | "sentiment"; label: string; color: string; ready: boolean }[] = [
  { key: "disclosure", label: "공시", color: "#ffb86c", ready: true },
  { key: "news", label: "뉴스", color: "#8be9fd", ready: true },
  { key: "volume", label: "거래량", color: "#bd93f9", ready: true },
  { key: "price", label: "급등락", color: "#50fa7b", ready: true },
  { key: "quant", label: "퀀트 시그널", color: "#50fa7b", ready: false },
  { key: "sentiment", label: "감성", color: "#ff79c6", ready: false },
];

interface Props {
  stockId: number;
  symbol: string;
  stockName: string;
  currentPrice: number;
  dayChange: number | null;
  dayChangeRate: number | null;
  tab: ChartTab;
  onTabChange: (t: ChartTab) => void;
  /** 차트 이벤트 마커 클릭 → 이벤트 패널로 */
  onEventClick: (eventId: number) => void;
  /** 요약의 "이벤트 보기" → 이벤트 패널로 */
  onShowEvents: () => void;
  orderLines: OrderLine[];
  onCancelOrderLine: (orderId: number) => void;
}

/**
 * 차트 높이 — 패널이 옆 열(주문폼) 높이만큼 늘어나면 남는 공간까지 차트가 채운다(시안처럼 빈 바닥이 없게).
 * 최소 높이는 좁은 화면 320, 그 외 470.
 */
function useChartHeight(box: React.RefObject<HTMLDivElement | null>) {
  const [h, setH] = useState(470);
  useEffect(() => {
    const el = box.current;
    const measure = () => {
      const min = window.innerWidth < 640 ? 320 : 470;
      setH(Math.max(min, Math.floor(el?.clientHeight ?? 0)));
    };
    measure();
    window.addEventListener("resize", measure);
    const ro = el && typeof ResizeObserver !== "undefined" ? new ResizeObserver(measure) : null;
    if (el && ro) ro.observe(el);
    return () => {
      window.removeEventListener("resize", measure);
      ro?.disconnect();
    };
  }, [box]);
  return h;
}

/** 시안 Main의 차트 패널 — 종목 탭 · 타임프레임 · 지표/캔들 · 이벤트 레이어 · 왼쪽 그리기 도구 · 차트 · 면책 문구 */
export default function ChartPanel(props: Props) {
  const { tab, onTabChange } = props;
  return (
    <Panel
      tabs={CHART_TABS}
      active={tab}
      onTabChange={(k) => onTabChange(k as ChartTab)}
      actions={["plus", "sliders", "expand"]}
      right={<IconBtn name="layout" label="차트 레이아웃 (준비 중)" size={28} iconSize={15} aria-disabled="true" />}
      className="flex-[999_1_520px]"
    >
      {tab === "chart" && <ChartBody {...props} />}
      {tab === "summary" && (
        <SummaryPanel
          stockId={props.stockId}
          symbol={props.symbol}
          bare
          onNavigate={(t) => (t === "news" ? onTabChange("news") : props.onShowEvents())}
        />
      )}
      {tab === "news" && <NewsPanel stockId={props.stockId} bare />}
      {tab === "flow" && (
        <div className="flex flex-col gap-4">
          <InvestorFlowPanel stockId={props.stockId} bare />
          <StockScoreCard stockId={props.stockId} bare />
        </div>
      )}
      {tab === "community" && <StockCommentPanel stockId={props.stockId} bare />}
      {tab === "watchlist" && <WatchlistTab currentStockId={props.stockId} />}
    </Panel>
  );
}

function ChartBody({ stockId, symbol, stockName, currentPrice, dayChange, dayChangeRate, onTabChange, onEventClick, orderLines, onCancelOrderLine }: Props) {
  const [interval, setInterval] = useState("1d");
  const [enabledIndicators, setEnabledIndicators] = useState<IndicatorKey[]>(["MA5", "MA20"]);
  const [showVwap, setShowVwap] = useState(false);
  const [subPane, setSubPane] = useState<SubPane>("none");
  const [menuOpen, setMenuOpen] = useState(false);
  const [layers, setLayers] = useState<Record<EventLayer, boolean>>({ disclosure: true, news: true, volume: true, price: true });
  const [activeDrawingTool, setActiveDrawingTool] = useState<DrawingTool | null>(null);
  const [drawings, setDrawings] = useState<Drawing[]>([]);
  const [hideDrawings, setHideDrawings] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const chartBox = useRef<HTMLDivElement>(null);
  const height = useChartHeight(chartBox);

  const { candles, events, loading } = useStockChart(stockId, interval);
  const { data: vwapData } = useVwap(stockId);

  // 드로잉은 종목+봉 간격별로 로컬에만 저장한다(서버 동기화 없음) — 차트 분석 메모는 개인 작업 흔적이라
  // 로그인 여부와 무관하게 남기고 싶을 때가 많다.
  const drawingsKey = `monticker:chartDrawings:${stockId}:${interval}`;
  useEffect(() => {
    try {
      const raw = localStorage.getItem(drawingsKey);
      setDrawings(raw ? JSON.parse(raw) : []);
    } catch {
      setDrawings([]);
    }
  }, [drawingsKey]);
  const persistDrawings = useCallback((next: Drawing[]) => {
    setDrawings(next);
    try { localStorage.setItem(drawingsKey, JSON.stringify(next)); } catch { /* 저장 실패해도 화면 상태는 유지 */ }
  }, [drawingsKey]);

  useEffect(() => {
    if (!menuOpen) return;
    const h = (e: MouseEvent) => menuRef.current && !menuRef.current.contains(e.target as Node) && setMenuOpen(false);
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [menuOpen]);

  // 이벤트 레이어 칩 — 꺼진 레이어의 마커는 차트에서 뺀다. 참조가 바뀌면 차트가 다시 그려지므로 memo.
  const visibleEvents = useMemo(
    () => events.filter((e) => layers[(EVENT_META[e.eventType]?.layer ?? "price") as EventLayer]),
    [events, layers],
  );

  const last = candles[candles.length - 1];
  const intervalLabel = INTERVALS.find((i) => i.value === interval)?.label ?? interval;
  const up = (dayChange ?? 0) >= 0;
  const toggleIndicator = (k: IndicatorKey) => setEnabledIndicators((p) => (p.includes(k) ? p.filter((x) => x !== k) : [...p, k]));
  const pickTool = (t: DrawingTool) => setActiveDrawingTool((cur) => (cur === t ? null : t));

  const tools: { icon: Parameters<typeof Icon>[0]["name"]; label: string; on?: boolean; onClick?: () => void }[] = [
    { icon: "cross", label: "십자선", on: activeDrawingTool === null, onClick: () => setActiveDrawingTool(null) },
    { icon: "line", label: "추세선 (두 번 클릭)", on: activeDrawingTool === "TREND_LINE", onClick: () => pickTool("TREND_LINE") },
    { icon: "hlines", label: "수평선 (한 번 클릭)", on: activeDrawingTool === "HORIZONTAL_LINE", onClick: () => pickTool("HORIZONTAL_LINE") },
    { icon: "pencil", label: "펜 (준비 중)" },
    { icon: "text", label: "텍스트 (준비 중)" },
    { icon: "ruler", label: "측정 (준비 중)" },
    { icon: "zoom", label: "확대 (준비 중) — 휠/하단 슬라이더로 확대할 수 있어요" },
    { icon: "magnet", label: "자석 모드 (준비 중)" },
    { icon: "lock", label: "그리기 잠금 (준비 중)" },
    { icon: "eye", label: hideDrawings ? "그리기 보이기" : "그리기 숨기기", on: hideDrawings, onClick: () => setHideDrawings((v) => !v) },
    { icon: "trash", label: "모두 지우기", onClick: drawings.length ? () => persistDrawings([]) : undefined },
  ];

  return (
    <>
      <SymbolTabs stockId={stockId} symbol={symbol} stockName={stockName} currentPrice={currentPrice} dayChangeRate={dayChangeRate} />

      <div className="flex flex-wrap items-center gap-x-3.5 gap-y-1.5 border-b border-tm-line pb-2">
        <div className="flex flex-wrap items-center">
          {INTERVALS.map((i) =>
            i.value ? (
              <button
                key={i.label}
                type="button"
                aria-pressed={interval === i.value}
                onClick={() => setInterval(i.value!)}
                className={cn("h-7 rounded-md px-[9px] text-xs", interval === i.value ? "bg-tm-raised font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg")}
              >
                {i.label}
              </button>
            ) : (
              <button key={i.label} type="button" disabled title={`${i.label}봉 (준비 중)`} className="h-7 cursor-not-allowed rounded-md px-[9px] text-xs text-tm-muted opacity-50">
                {i.label}
              </button>
            ),
          )}
        </div>
        <span className="h-[18px] w-px bg-tm-line" aria-hidden />
        <div className="relative" ref={menuRef}>
          <button type="button" aria-expanded={menuOpen} aria-haspopup="true" onClick={() => setMenuOpen((v) => !v)} className="flex items-center gap-1.5 text-xs text-tm-soft hover:text-dracula-fg">
            <Icon name="trend" size={15} />지표
          </button>
          {menuOpen && (
            <div className="absolute left-0 top-full z-20 mt-2 flex w-48 flex-col gap-1 rounded-[10px] border border-tm-line2 bg-tm-panel p-2 shadow-glow-line">
              <span className="px-1.5 text-2xs text-tm-muted">차트 위</span>
              {INDICATORS.map((o) => (
                <label key={o.key} className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 text-xs hover:bg-tm-raised">
                  <input type="checkbox" checked={enabledIndicators.includes(o.key)} onChange={() => toggleIndicator(o.key)} className="accent-dracula-purple" />
                  {o.label}
                </label>
              ))}
              <label className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 text-xs hover:bg-tm-raised">
                <input type="checkbox" checked={showVwap} onChange={() => setShowVwap((v) => !v)} className="accent-dracula-purple" />
                VWAP
              </label>
              <span className="mt-1 px-1.5 text-2xs text-tm-muted">보조 지표</span>
              {(["none", "volume", "rsi", "macd"] as SubPane[]).map((p) => (
                <label key={p} className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 text-xs hover:bg-tm-raised">
                  <input type="radio" name={`subpane-${stockId}`} checked={subPane === p} onChange={() => setSubPane(p)} className="accent-dracula-purple" />
                  {{ none: "없음", volume: "거래량", rsi: "RSI", macd: "MACD" }[p]}
                </label>
              ))}
            </div>
          )}
        </div>
        <button type="button" disabled title="차트 유형 (준비 중) — 지금은 캔들만 지원" className="flex cursor-not-allowed items-center gap-1.5 text-xs text-tm-soft">
          <Icon name="candles" size={15} />캔들
        </button>
        <div className="ml-auto flex flex-wrap gap-1.5" role="group" aria-label="이벤트 레이어">
          {LAYERS.map((l) => {
            const on = l.ready && layers[l.key as EventLayer];
            return (
              <button
                key={l.key}
                type="button"
                aria-pressed={on}
                disabled={!l.ready}
                title={l.ready ? undefined : `${l.label} 레이어 (준비 중)`}
                onClick={() => l.ready && setLayers((s) => ({ ...s, [l.key]: !s[l.key as EventLayer] }))}
                className={cn(
                  "inline-flex h-[26px] items-center gap-1.5 whitespace-nowrap rounded-full border border-tm-line2 px-2.5 text-xs font-medium",
                  on ? "text-dracula-fg" : "text-tm-muted",
                  !l.ready && "cursor-not-allowed opacity-50",
                )}
              >
                <span className="h-[7px] w-[7px] rounded-full" style={{ background: on ? l.color : "#44475a" }} />
                {l.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex min-h-0 min-w-0 flex-1 gap-1.5">
        <div className="flex flex-col gap-0.5 border-r border-tm-line pr-1.5" role="toolbar" aria-label="그리기 도구" aria-orientation="vertical">
          {tools.map((t) => (
            <IconBtn
              key={t.icon}
              name={t.icon}
              label={t.label}
              size={34}
              iconSize={17}
              aria-pressed={t.onClick ? !!t.on : undefined}
              aria-disabled={t.onClick ? undefined : "true"}
              onClick={t.onClick}
              className={cn(t.on && "bg-tm-raised text-dracula-purple", !t.onClick && "cursor-not-allowed opacity-40 hover:bg-transparent hover:text-tm-muted")}
            />
          ))}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <div className="text-xs text-tm-muted">
            {stockName} · {intervalLabel}{" "}
            {last ? (
              <span className="num">
                시 {fmtNum(last.open)} 고 {fmtNum(last.high)} 저 {fmtNum(last.low)} 종{" "}
                <span className={up ? "text-up" : "text-down"}>
                  {fmtNum(currentPrice || last.close)}
                  {dayChange != null && dayChangeRate != null && ` ${dayChange >= 0 ? "+" : ""}${fmtNum(dayChange)} (${dayChangeRate >= 0 ? "+" : ""}${dayChangeRate.toFixed(2)}%)`}
                </span>
              </span>
            ) : (
              <span className="num">—</span>
            )}
          </div>
          {/* 이 상자가 남는 높이를 차지하고, 그 높이를 재서 차트에 넘긴다(차트 자체는 높이를 정해 받아야 그린다) */}
          <div ref={chartBox} className="relative min-h-[320px] flex-1 sm:min-h-[470px]">
          <div className="absolute inset-x-0 top-0">
          {loading ? (
            <div className="grid animate-pulse place-items-center rounded-lg bg-tm-inner text-13 text-tm-muted" style={{ height }}>
              차트 로딩 중...
            </div>
          ) : (
            <StockChart
              candles={candles}
              events={visibleEvents}
              height={height}
              vwapData={showVwap ? vwapData : undefined}
              onEventClick={onEventClick}
              enabledIndicators={enabledIndicators}
              orderLines={orderLines}
              onCancelOrderLine={onCancelOrderLine}
              activeDrawingTool={activeDrawingTool}
              drawings={hideDrawings ? [] : drawings}
              onDrawingsChange={persistDrawings}
            />
          )}
          </div>
          </div>
          {!loading && candles.length > 0 && subPane !== "none" && (
            <div style={{ height: 100 }}>
              {subPane === "volume" && <VolumeChart candles={candles} height={100} />}
              {subPane === "rsi" && <IndicatorChart candles={candles} showRSI />}
              {subPane === "macd" && <IndicatorChart candles={candles} showMACD />}
            </div>
          )}
          <div className="flex flex-wrap justify-between gap-2 text-xs text-tm-muted">
            <span>이벤트와 가격 변동의 시간적 연관을 표시할 뿐, 인과관계를 단정하지 않습니다.</span>
            <button type="button" onClick={() => onTabChange("summary")} className="flex items-center gap-1.5 text-dracula-purple">
              <Icon name="info" size={14} />왜 움직였나 보기
            </button>
          </div>
        </div>
      </div>
    </>
  );
}

/** 차트 위 종목 탭 — 최근 본 종목(브라우저 로컬 기록). ×는 이 화면에서만 숨긴다. */
function SymbolTabs({ stockId, symbol, stockName, currentPrice, dayChangeRate }: { stockId: number; symbol: string; stockName: string; currentPrice: number; dayChangeRate: number | null }) {
  const { entries } = useRecentlyViewedStocks();
  const [hidden, setHidden] = useState<number[]>([]);
  const others = entries.filter((e) => e.stockId !== stockId && !hidden.includes(e.stockId)).slice(0, 4);
  const quotes = useQuotes(others.map((e) => e.stockId), 30_000);
  return (
    <div className="flex flex-wrap items-center gap-1">
      <span className="inline-flex h-7 items-center gap-1.5 rounded-md bg-tm-raised px-2.5 text-xs" aria-current="page">
        {stockName}
        <span className={cn("num", (dayChangeRate ?? 0) >= 0 ? "text-up" : "text-down")}>{currentPrice > 0 ? fmtNum(currentPrice) : "—"}</span>
      </span>
      {others.map((e) => {
        const q = quotes[e.stockId];
        return (
          <span key={e.stockId} className="inline-flex h-7 items-center gap-1 rounded-md pl-2.5 pr-0.5 text-xs hover:bg-tm-raised/60">
            <Link href={`/stocks/${e.symbol}`} className="flex items-center gap-1.5 text-dracula-fg hover:text-dracula-fg">
              {e.name}
              <span className={cn("num", q ? (q.changeRate >= 0 ? "text-up" : "text-down") : "text-tm-muted")}>{q ? fmtNum(q.price) : "—"}</span>
            </Link>
            <IconBtn name="x" label={`${e.name} 탭 닫기`} size={20} iconSize={11} onClick={() => setHidden((h) => [...h, e.stockId])} />
          </span>
        );
      })}
      <Link href="/stocks/search" className="h-7 px-2.5 text-xs leading-7 text-tm-muted hover:text-dracula-fg" aria-label={`종목 추가 (현재 ${symbol})`}>
        + 종목 추가
      </Link>
    </div>
  );
}

interface WatchGroup { id: number; name: string; items: { id: number; stockId: number; symbol: string; name: string }[] }

/** 관심종목 탭 — 내 관심 그룹의 종목을 시세와 함께 */
function WatchlistTab({ currentStockId }: { currentStockId: number }) {
  const { isLoggedIn } = useAuth();
  const { data: groups = [], isLoading } = useQuery<WatchGroup[]>({
    queryKey: ["watchlist", "groups", "stock-terminal"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
  });
  const items = Array.from(new Map(groups.flatMap((g) => g.items ?? []).map((i) => [i.stockId, i])).values());
  const quotes = useQuotes(items.map((i) => i.stockId));
  if (!isLoggedIn) return <p className="m-0 py-6 text-center text-13 text-tm-muted">로그인이 필요합니다.</p>;
  if (isLoading) return <div className="h-24 animate-pulse rounded-lg bg-tm-inner" />;
  if (items.length === 0) return <p className="m-0 py-6 text-center text-13 text-tm-muted">관심종목이 없습니다. 상단의 &lsquo;관심종목 추가&rsquo;로 담아 보세요.</p>;
  return (
    <ul className="m-0 list-none p-0">
      {items.map((i) => {
        const q = quotes[i.stockId];
        return (
          <li key={i.stockId} className={cn("border-b border-tm-line", i.stockId === currentStockId && "bg-tm-raised")}>
            <Link href={`/stocks/${i.symbol}`} className="flex items-center gap-2.5 px-1.5 py-2 text-dracula-fg hover:text-dracula-fg">
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="text-13 font-semibold">{i.name}</span>
                <span className="num text-2xs text-tm-muted">{i.symbol}</span>
              </span>
              <span className="flex min-w-[76px] flex-col items-end">
                <span className="num text-13">{q ? fmtNum(q.price) : "—"}</span>
                <span className={cn("num text-2xs", q ? (q.changeRate >= 0 ? "text-up" : "text-down") : "text-tm-muted")}>
                  {q ? `${q.changeRate >= 0 ? "+" : ""}${q.changeRate.toFixed(2)}%` : "—"}
                </span>
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

