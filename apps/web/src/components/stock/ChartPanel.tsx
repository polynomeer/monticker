"use client";

import { useFillHeight } from "@/hooks/useFillHeight";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Icon, IconBtn, Panel, fmtNum } from "@/components/terminal";
import StockChart from "./chart/StockChart";
import type { ChartType, DrawingTool, IndicatorKey, OrderLine, SentimentMarker, SignalMarker } from "./chart/types";
import { toChartInterval } from "./chart/chartTime";
import { useChartDrawings, useChartPrefs } from "./chart/useChartDrawings";
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

/** 시안의 타임프레임 7개 + 기존에 있던 장기 범위 2개. 3분·15분·1시간은 서버가 분봉을 묶어 준다(ADR-076). */
const INTERVALS: { label: string; value: string | null }[] = [
  { label: "1분", value: "1m" },
  { label: "3분", value: "3m" },
  { label: "15분", value: "15m" },
  { label: "1시간", value: "1h" },
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

const CHART_TYPES: { key: ChartType; label: string }[] = [
  { key: "candle", label: "캔들" },
  { key: "line", label: "라인" },
  { key: "area", label: "영역" },
  { key: "heikin-ashi", label: "하이킨아시" },
];

/** 예전 드로잉 키(종목 id·간격별)를 옮길 때 훑을 간격 */
const LEGACY_DRAWING_INTERVALS = INTERVALS.map((i) => i.value).filter((v): v is string => v != null);

/** 그린 것을 저장하는 도구 — 숨기기 중에는 쓸 수 없다(보이지 않는 목록에 덧붙이지 않게) */
const SAVING_TOOLS: ReadonlySet<DrawingTool> = new Set(["TREND_LINE", "HORIZONTAL_LINE", "PEN", "TEXT"]);

type ChartLayer = EventLayer | "quant" | "sentiment";

const LAYERS: { key: ChartLayer; label: string; color: string }[] = [
  { key: "disclosure", label: "공시", color: "#ffb86c" },
  { key: "news", label: "뉴스", color: "#8be9fd" },
  { key: "volume", label: "거래량", color: "#bd93f9" },
  { key: "price", label: "급등락", color: "#50fa7b" },
  { key: "quant", label: "퀀트 시그널", color: "#50fa7b" },
  { key: "sentiment", label: "감성", color: "#ff79c6" },
];

const NO_DRAWINGS: never[] = [];

interface StockSignal { id: number; ruleSetId: string; ruleSetName: string; origin: "OWNED" | "SUBSCRIBED"; direction: "BUY" | "SELL"; signalTime: string; mode: string }

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

type ToolButton = {
  key: string;
  icon: Parameters<typeof Icon>[0]["name"];
  label: string;
  on?: boolean;
  /** 없으면 지금 쓸 수 없는 버튼(aria-disabled) */
  onClick?: () => void;
};

function ChartBody({ stockId, symbol, stockName, currentPrice, dayChange, dayChangeRate, onTabChange, onEventClick, orderLines, onCancelOrderLine }: Props) {
  const [interval, setInterval] = useState("1d");
  const [enabledIndicators, setEnabledIndicators] = useState<IndicatorKey[]>(["MA5", "MA20"]);
  const [showVwap, setShowVwap] = useState(false);
  const [subPane, setSubPane] = useState<SubPane>("none");
  const [menuOpen, setMenuOpen] = useState(false);
  const [layers, setLayers] = useState<Record<ChartLayer, boolean>>({ disclosure: true, news: true, volume: true, price: true, quant: false, sentiment: false });
  const { isLoggedIn } = useAuth();
  const [activeDrawingTool, setActiveDrawingTool] = useState<DrawingTool | null>(null);
  const [hideDrawings, setHideDrawings] = useState(false);
  const [locked, setLocked] = useState(false);
  const [prefs, setPrefs] = useChartPrefs();
  const [drawings, persistDrawings] = useChartDrawings(symbol, stockId, isLoggedIn, LEGACY_DRAWING_INTERVALS);
  const [toolFocus, setToolFocus] = useState(0);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [chartBox, height] = useFillHeight(470, 320);

  const { candles, events, loading } = useStockChart(stockId, interval);
  const { data: vwapData } = useVwap(stockId);

  // 드로잉은 사용자·종목별로 이 브라우저에만 저장한다(서버 동기화 없음). (시각, 가격)으로 저장해
  // 봉 간격을 바꿔도 같은 자리에 다시 놓인다 — chart/drawingStorage.ts

  // Esc — 켜 둔 그리기 도구를 끄고 십자선으로 돌아간다(그리던 점·측정도 버린다)
  useEffect(() => {
    if (!activeDrawingTool) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setActiveDrawingTool(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [activeDrawingTool]);
  const onToolDone = useCallback(() => setActiveDrawingTool(null), []);

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

  // 퀀트 시그널 레이어 — 내 전략·구독 전략의 이 종목 신호(로그인 필요). 켤 때만 불러온다.
  const { data: signals = [] } = useQuery<StockSignal[]>({
    queryKey: ["quant", "stock-signals", stockId],
    queryFn: async () => {
      const r = await authFetch(`/api/quant/stocks/${stockId}/signals?days=1095`);
      if (!r.ok) throw new Error("퀀트 시그널 조회 실패");
      return r.json();
    },
    enabled: isLoggedIn && layers.quant,
    staleTime: 60_000,
  });
  const signalMarkers: SignalMarker[] = useMemo(
    () => (layers.quant ? signals.map((s) => ({ id: s.id, time: Math.floor(new Date(s.signalTime).getTime() / 1000), direction: s.direction, label: s.ruleSetName })) : []),
    [signals, layers.quant],
  );
  // 감성 레이어 — 감성 점수가 있는 이벤트(주로 뉴스)
  const sentimentMarkers: SentimentMarker[] = useMemo(
    () => (layers.sentiment ? events.filter((e) => e.sentimentScore != null).map((e) => ({ id: e.id, time: e.time, score: e.sentimentScore as number, title: e.title })) : []),
    [events, layers.sentiment],
  );
  const layerCount: Partial<Record<ChartLayer, number>> = { quant: signals.length, sentiment: events.filter((e) => e.sentimentScore != null).length };

  const last = candles[candles.length - 1];
  const intervalLabel = INTERVALS.find((i) => i.value === interval)?.label ?? interval;
  const up = (dayChange ?? 0) >= 0;
  const toggleIndicator = (k: IndicatorKey) => setEnabledIndicators((p) => (p.includes(k) ? p.filter((x) => x !== k) : [...p, k]));
  const pickTool = (t: DrawingTool) => setActiveDrawingTool((cur) => (cur === t ? null : t));
  const tool = (key: DrawingTool, icon: ToolButton["icon"], label: string): ToolButton => {
    const blocked = hideDrawings && SAVING_TOOLS.has(key);
    return {
      key, icon,
      label: blocked ? `${label} — 그리기를 숨긴 동안은 쓸 수 없어요` : `${label} · Esc로 해제`,
      on: activeDrawingTool === key,
      onClick: blocked ? undefined : () => pickTool(key),
    };
  };

  const tools: ToolButton[] = [
    { key: "cross", icon: "cross", label: locked ? "십자선" : "십자선 — 그린 것을 끌어 옮기고, 클릭하면 지워요", on: activeDrawingTool === null, onClick: () => setActiveDrawingTool(null) },
    tool("TREND_LINE", "line", "추세선 (두 번 클릭)"),
    tool("HORIZONTAL_LINE", "hlines", "수평선 (한 번 클릭)"),
    tool("PEN", "pencil", "펜 (끌어서 그리기)"),
    tool("TEXT", "text", "텍스트 (클릭한 자리에 입력)"),
    tool("MEASURE", "ruler", "측정 (두 점 클릭 — 가격·%·봉 수)"),
    tool("ZOOM", "zoom", "구간 확대 (두 점 클릭)"),
    { key: "magnet", icon: "magnet", label: prefs.magnet ? "자석 끄기" : "자석 — 점을 가까운 시·고·저·종 가격에 붙여요", on: prefs.magnet, onClick: () => setPrefs({ magnet: !prefs.magnet }) },
    { key: "lock", icon: "lock", label: locked ? "그리기 잠금 해제" : "그리기 잠금 — 그린 것을 옮기거나 지우지 않게", on: locked, onClick: () => setLocked((v) => !v) },
    {
      key: "eye", icon: "eye", label: hideDrawings ? "그리기 보이기" : "그리기 숨기기", on: hideDrawings,
      onClick: () => {
        // 숨기는 동안 저장 도구를 쓰면 보이지 않는 목록에 덧붙게 되므로 도구도 끈다
        if (!hideDrawings && activeDrawingTool && SAVING_TOOLS.has(activeDrawingTool)) setActiveDrawingTool(null);
        setHideDrawings((v) => !v);
      },
    },
    {
      key: "trash", icon: "trash",
      label: locked ? "모두 지우기 — 잠금을 풀어야 지울 수 있어요" : drawings.length ? `모두 지우기 (${drawings.length}개)` : "모두 지우기 — 그린 것이 없어요",
      onClick: drawings.length && !locked ? () => persistDrawings([]) : undefined,
    },
  ];

  // 툴바 키보드 — 위/아래(또는 좌/우) 화살표로 버튼 사이 이동, Home/End로 처음/끝. Tab은 툴바 하나만 거친다.
  const onToolbarKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, (i: number) => number> = {
      ArrowDown: (i) => (i + 1) % tools.length,
      ArrowRight: (i) => (i + 1) % tools.length,
      ArrowUp: (i) => (i - 1 + tools.length) % tools.length,
      ArrowLeft: (i) => (i - 1 + tools.length) % tools.length,
      Home: () => 0,
      End: () => tools.length - 1,
    };
    const move = keys[e.key];
    if (!move) return;
    e.preventDefault();
    const next = move(toolFocus);
    setToolFocus(next);
    toolbarRef.current?.querySelectorAll<HTMLButtonElement>("button")[next]?.focus();
  };

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
        <div className="flex items-center gap-0.5" role="group" aria-label="차트 유형">
          <Icon name="candles" size={15} className="mr-1 text-tm-soft" aria-hidden />
          {CHART_TYPES.map((t) => (
            <button
              key={t.key}
              type="button"
              aria-pressed={prefs.chartType === t.key}
              onClick={() => setPrefs({ chartType: t.key })}
              title={t.key === "heikin-ashi" ? "하이킨아시 — 평균을 낸 봉(툴팁은 실제 시세)" : undefined}
              className={cn("h-7 rounded-md px-2 text-xs", prefs.chartType === t.key ? "bg-tm-raised font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg")}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="ml-auto flex flex-wrap gap-1.5" role="group" aria-label="이벤트 레이어">
          {LAYERS.map((l) => {
            // 퀀트 시그널은 내 전략·구독 전략이라 로그인해야 볼 수 있다
            const ready = l.key !== "quant" || isLoggedIn;
            const on = ready && layers[l.key];
            const count = on ? layerCount[l.key] : undefined;
            return (
              <button
                key={l.key}
                type="button"
                aria-pressed={on}
                disabled={!ready}
                title={
                  !ready ? "로그인하면 내 전략·구독 전략의 신호를 볼 수 있어요"
                    : l.key === "quant" ? "내 전략·구독 전략의 이 종목 신호"
                      : l.key === "sentiment" ? "뉴스 감성 점수(초록 긍정·빨강 부정)" : undefined
                }
                onClick={() => ready && setLayers((s) => ({ ...s, [l.key]: !s[l.key] }))}
                className={cn(
                  "inline-flex h-[26px] items-center gap-1.5 whitespace-nowrap rounded-full border border-tm-line2 px-2.5 text-xs font-medium",
                  on ? "text-dracula-fg" : "text-tm-muted",
                  !ready && "cursor-not-allowed opacity-50",
                )}
              >
                <span className="h-[7px] w-[7px] rounded-full" style={{ background: on ? l.color : "#44475a" }} />
                {l.label}
                {count != null && <span className="num text-tm-muted">{count}</span>}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex min-h-0 min-w-0 flex-1 gap-1.5">
        <div
          ref={toolbarRef}
          className="flex flex-col gap-0.5 border-r border-tm-line pr-1.5"
          role="toolbar"
          aria-label="그리기 도구"
          aria-orientation="vertical"
          onKeyDown={onToolbarKey}
        >
          {tools.map((t, i) => (
            <IconBtn
              key={t.key}
              name={t.icon}
              label={t.label}
              size={34}
              iconSize={17}
              tabIndex={i === toolFocus ? 0 : -1}
              onFocus={() => setToolFocus(i)}
              // 지우기만 동작 버튼, 나머지는 켜고 끄는 토글
              aria-pressed={t.key === "trash" ? undefined : !!t.on}
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
              signalMarkers={signalMarkers}
              sentimentMarkers={sentimentMarkers}
              interval={toChartInterval(interval)}
              chartType={prefs.chartType}
              activeDrawingTool={activeDrawingTool}
              drawings={hideDrawings ? NO_DRAWINGS : drawings}
              onDrawingsChange={persistDrawings}
              magnet={prefs.magnet}
              drawingsLocked={locked}
              onDrawingToolDone={onToolDone}
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

