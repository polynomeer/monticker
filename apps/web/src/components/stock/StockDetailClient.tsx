"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Card } from "@/components/ui/Card";
import StockChart from "./chart/StockChart";
import IndicatorChart from "./IndicatorChart";
import VolumeChart from "./VolumeChart";
import EventTimeline from "./EventTimeline";
import NewsPanel from "./NewsPanel";
import AlertPanel from "./AlertPanel";
import SummaryPanel from "./SummaryPanel";
import WatchlistAddButton from "./WatchlistAddButton";
import OrderBook from "./OrderBook";
import InvestorFlowPanel from "./InvestorFlowPanel";
import StockScoreCard from "./StockScoreCard";
import TradePanel from "@/components/paper/TradePanel";
import { useStockChart } from "@/hooks/useStockChart";
import { useVwap } from "@/hooks/useVwap";
import { useStockPrice } from "@/hooks/useStockPrice";

interface Props { stockId: number; symbol: string; stockName: string; }

const INTERVALS = [
  { label: "1분", value: "1m" },
  { label: "일봉", value: "1d" },
  { label: "1주", value: "1w" },
  { label: "1달", value: "1M" },
  { label: "3달", value: "3M" },
  { label: "1년", value: "1Y" },
];

const SUB_TABS = [
  { label: "거래량", value: "volume" },
  { label: "RSI", value: "rsi" },
  { label: "MACD", value: "macd" },
] as const;

const LEFT_TABS = [
  { label: "AI 요약", value: "summary" },
  { label: "뉴스", value: "news" },
  { label: "이벤트", value: "events" },
] as const;

function pill(active: boolean) {
  return active
    ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold"
    : "text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg hover:bg-gray-100 dark:hover:bg-white/5";
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

export default function StockDetailClient({ stockId, symbol, stockName }: Props) {
  const [interval, setInterval] = useState("1d");
  const [subTab, setSubTab] = useState<typeof SUB_TABS[number]["value"]>("volume");
  const [leftTab, setLeftTab] = useState<typeof LEFT_TABS[number]["value"]>("summary");
  const [showVwap, setShowVwap] = useState(false);
  const [alertHighlight, setAlertHighlight] = useState(false);
  const [chartHeight, setChartHeight] = useState(300);
  const chartAreaRef = useRef<HTMLDivElement>(null);

  // StockChart/EChartsAdapter는 height를 고정 px로만 받는다 — 유동 flex 높이에
  // 맞춰 리사이즈되도록 실제 렌더된 컨테이너 높이를 관찰해서 넘겨준다.
  useEffect(() => {
    const el = chartAreaRef.current;
    if (!el) return;
    const observer = new ResizeObserver(entries => {
      const h = entries[0]?.contentRect.height;
      if (h && h > 0) setChartHeight(Math.round(h));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const { candles, events, loading } = useStockChart(stockId, interval);
  const { candles: dailyCandles } = useStockChart(stockId, "1d");
  const { data: vwapData } = useVwap(stockId);
  const { price: livePrice } = useStockPrice(stockId);
  const searchParams = useSearchParams();

  // 스크리너 등에서 "알림 만들기" 바로가기로 들어온 경우 알림 카드를 잠깐 강조
  useEffect(() => {
    if (searchParams.get("openAlert") !== "1") return;
    setAlertHighlight(true);
    const t = setTimeout(() => setAlertHighlight(false), 1800);
    return () => clearTimeout(t);
    // searchParams는 마운트 시점 값만 필요 — eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const latestDaily = dailyCandles[dailyCandles.length - 1];
  const prevDaily = dailyCandles[dailyCandles.length - 2];
  const currentPrice = livePrice?.price ?? latestDaily?.close ?? 0;
  const prevClose = prevDaily?.close ?? null;
  const changeAmount = prevClose != null ? currentPrice - prevClose : null;
  const changeRate = changeAmount != null && prevClose ? (changeAmount / prevClose) * 100 : null;
  const isUp = (changeAmount ?? 0) >= 0;

  return (
    <div className="flex h-[calc(100vh-64px)] min-h-0 flex-col animate-fade-up">
      {/* ── 종목 헤더 스트립 ─────────────────────────────────── */}
      <div className="flex flex-0 items-center gap-5 border-b border-gray-100 dark:border-white/5 px-5 py-3">
        <div className="flex items-center gap-3">
          <div>
            <div className="flex items-baseline gap-2">
              <span className="text-base font-extrabold tracking-tight text-gray-900 dark:text-dracula-fg">{stockName}</span>
              <span className="text-[11px] text-gray-400 dark:text-dracula-comment">{symbol}</span>
            </div>
          </div>
          <div className="flex items-baseline gap-2">
            <span className="font-mono text-lg font-extrabold tabular-nums text-gray-900 dark:text-dracula-fg">₩{fmt(currentPrice)}</span>
            {changeAmount != null && changeRate != null && (
              <span className={`inline-flex items-center rounded px-1.5 py-0.5 font-mono text-xs font-semibold tabular-nums ${
                isUp ? "bg-market-up/15 text-market-up" : "bg-market-down/15 text-market-down"
              }`}>
                {isUp ? "▲" : "▼"} {fmt(Math.abs(changeAmount))} ({isUp ? "+" : ""}{changeRate.toFixed(2)}%)
              </span>
            )}
          </div>
          <WatchlistAddButton stockId={stockId} />
        </div>

        <div className="flex flex-1 justify-center">
          <div className="flex items-center gap-0.5 rounded-lg border border-gray-100 dark:border-white/5 bg-gray-50 dark:bg-dracula-surface p-1">
            {INTERVALS.map(i => (
              <button key={i.value} onClick={() => setInterval(i.value)}
                className={`rounded-md px-2.5 py-1 text-xs transition-all duration-150 active:scale-95 ${pill(interval === i.value)}`}>
                {i.label}
              </button>
            ))}
          </div>
        </div>

        <span className="text-[11px] text-gray-400 dark:text-dracula-comment">
          {loading ? "로딩 중..." : `총 ${candles.length}개 캔들`}
        </span>
      </div>

      {/* ── 3컬럼 메인 그리드 ────────────────────────────────── */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3.5 overflow-hidden p-3.5 lg:grid-cols-[1.55fr_1.05fr_0.85fr]">

        {/* ===== 좌측: 차트 ===== */}
        <div className="flex min-h-0 flex-col gap-3.5">
          <Card className="flex flex-[1.35] min-h-0 flex-col overflow-hidden p-0">
            <div className="flex flex-0 items-center gap-2 border-b border-gray-100 dark:border-white/5 px-3.5 py-2">
              <span className="text-xs font-bold text-gray-900 dark:text-dracula-fg">차트</span>
              <div className="flex-1" />
              <button
                onClick={() => setShowVwap(v => !v)}
                className={`rounded-md border px-2 py-0.5 text-[11px] font-semibold transition-colors ${
                  showVwap
                    ? "border-dracula-purple text-dracula-purple bg-dracula-purple/10"
                    : "border-gray-200 dark:border-dracula-line text-gray-500 dark:text-dracula-comment hover:border-gray-300 dark:hover:border-dracula-comment"
                }`}
              >VWAP</button>
            </div>

            <div className="flex flex-1 min-h-0 flex-col">
              <div ref={chartAreaRef} className="min-h-0 flex-1 px-2 pt-2">
                {loading ? (
                  <div className="flex h-full items-center justify-center text-sm text-gray-400 dark:text-dracula-comment animate-pulse">
                    차트 로딩 중...
                  </div>
                ) : (
                  <StockChart
                    candles={candles}
                    events={events}
                    height={chartHeight}
                    vwapData={showVwap ? vwapData : undefined}
                  />
                )}
              </div>

              <div className="flex flex-0 items-center gap-1 px-3.5 pt-1.5">
                {SUB_TABS.map(t => (
                  <button key={t.value} onClick={() => setSubTab(t.value)}
                    className={`rounded-md px-2 py-1 text-[11px] font-semibold transition-colors ${pill(subTab === t.value)}`}>
                    {t.label}
                  </button>
                ))}
              </div>
              <div className="flex-0 px-2 pb-2 pt-1" style={{ height: 110 }}>
                {!loading && subTab === "volume" && candles.length > 0 && (
                  <VolumeChart candles={candles} height={110} />
                )}
                {!loading && subTab === "rsi" && candles.length > 0 && (
                  <IndicatorChart candles={candles} showRSI />
                )}
                {!loading && subTab === "macd" && candles.length > 0 && (
                  <IndicatorChart candles={candles} showMACD />
                )}
              </div>
            </div>
          </Card>

          <Card className="flex flex-1 min-h-0 flex-col overflow-hidden p-0">
            <div className="flex flex-0 items-center gap-1 px-3.5 pt-2.5">
              {LEFT_TABS.map(t => (
                <button key={t.value} onClick={() => setLeftTab(t.value)}
                  className={`-mb-px border-b-2 px-1 pb-2 text-[13px] font-bold transition-colors ${
                    leftTab === t.value
                      ? "border-dracula-purple text-gray-900 dark:text-dracula-fg"
                      : "border-transparent text-gray-400 dark:text-dracula-comment hover:text-gray-700 dark:hover:text-dracula-fg"
                  }`}>
                  {t.label}
                </button>
              ))}
            </div>
            <div className="mx-3.5 h-px bg-gray-100 dark:border-white/5 dark:bg-white/5" />
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              {leftTab === "summary" && <SummaryPanel stockId={stockId} symbol={symbol} bare />}
              {leftTab === "news" && <NewsPanel stockId={stockId} bare />}
              {leftTab === "events" && <EventTimeline stockId={stockId} bare />}
            </div>
          </Card>
        </div>

        {/* ===== 가운데: 호가 · 모의투자 · 수급 ===== */}
        <div className="flex min-h-0 flex-col gap-3.5">
          <div className="flex-[1.1] min-h-0 overflow-y-auto">
            <OrderBook stockId={stockId} />
          </div>

          <Card className="flex flex-[0.92] min-h-0 flex-col overflow-hidden p-0">
            <div className="flex flex-0 items-center gap-2 border-b border-gray-100 dark:border-white/5 px-3.5 py-2.5">
              <span className="text-xs font-bold text-gray-900 dark:text-dracula-fg">모의투자</span>
              <span className="rounded bg-gray-100 dark:bg-dracula-line px-1.5 py-0.5 text-[10px] font-semibold text-gray-500 dark:text-dracula-comment">Paper</span>
            </div>
            <TradePanel stock={{ id: stockId, symbol, name: stockName }} currentPrice={currentPrice} />
          </Card>

          <div className="flex-[0.55] min-h-0 overflow-y-auto">
            <InvestorFlowPanel stockId={stockId} />
          </div>
        </div>

        {/* ===== 우측: 스코어 · 알림 ===== */}
        <div className="flex min-h-0 flex-col gap-3.5">
          <div className="flex-[0.85] min-h-0 overflow-y-auto">
            <StockScoreCard stockId={stockId} />
          </div>

          <Card
            className={`flex flex-1 min-h-0 flex-col overflow-hidden p-0 transition-shadow duration-300 ${
              alertHighlight ? "ring-2 ring-dracula-purple" : ""
            }`}
          >
            <div className="flex flex-0 items-center gap-1.5 border-b border-gray-100 dark:border-white/5 px-3.5 py-2.5">
              <span className="text-xs font-bold text-gray-900 dark:text-dracula-fg">가격 알림</span>
              <span className="text-[11px] text-gray-400 dark:text-dracula-comment">({symbol})</span>
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto p-3.5">
              <AlertPanel stockId={stockId} symbol={symbol} bare />
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
