"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { KV, Notice, Panel, PanelCol, PanelRow, SymbolPill, TerminalPage, fmtNum, type TopStat } from "@/components/terminal";
import type { OrderLine } from "./chart/types";
import ChartPanel, { type ChartTab } from "./ChartPanel";
import EventTimeline from "./EventTimeline";
import AlertPanel from "./AlertPanel";
import WatchlistAddButton from "./WatchlistAddButton";
import OrderBook from "./OrderBook";
import DepthPanel from "./DepthPanel";
import OrderForm from "./OrderForm";
import PositionsPanel from "./PositionsPanel";
import PaperConditionalPanel from "./PaperConditionalPanel";
import RecentTrades from "./RecentTrades";
import StockScoreCard from "./StockScoreCard";
import { fmtKrwCompact, fmtShares, useQuotes } from "./parts";
import { useStockChart } from "@/hooks/useStockChart";
import { useStockPrice } from "@/hooks/useStockPrice";
import { useRecentlyViewedStocks } from "@/hooks/useRecentlyViewedStocks";
import { useActiveBrokerageOrdersForSymbol, useBrokerageAccount, useCancelBrokerageOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { usePaperOpenOrders, usePaperOrder } from "@/hooks/usePaperTrade";
import { useAuth } from "@/hooks/useAuth";
import { cn } from "@/lib/utils";

interface Props { stockId: number; symbol: string; stockName: string; market: string; }

type OrderTab = "order" | "conditional" | "alert";
type EventsTab = "events" | "depth";
type BookTab = "book" | "ticks" | "summary";

/** 일간 수익률의 표준편차(%) — 최근 n거래일 */
function dailySigma(closes: number[], n = 20) {
  const c = closes.slice(-(n + 1));
  if (c.length < 3) return null;
  const r = c.slice(1).map((v, i) => (v - c[i]) / c[i]);
  const m = r.reduce((s, v) => s + v, 0) / r.length;
  const sd = Math.sqrt(r.reduce((s, v) => s + (v - m) ** 2, 0) / (r.length - 1));
  return sd * 100;
}

/**
 * 트레이딩 · 종목 상세 — 시안 Main.dc.html.
 * [주문 + 이벤트] · [호가] · [차트] 3열 + 하단 [보유/미체결/조건부/체결/정산] 패널.
 * 시안에 없던 기존 기능(요약·뉴스·투자자 동향·토론·스코어·가격 알림)은 각 패널의 탭으로 옮겼다.
 */
export default function StockDetailClient({ stockId, symbol, stockName, market }: Props) {
  const [chartTab, setChartTab] = useState<ChartTab>("chart");
  const [orderTab, setOrderTab] = useState<OrderTab>("order");
  const [eventsTab, setEventsTab] = useState<EventsTab>("events");
  const [bookTab, setBookTab] = useState<BookTab>("book");
  const [alertHighlight, setAlertHighlight] = useState(false);
  const [highlightEventId, setHighlightEventId] = useState<number | null>(null);
  const { record: recordRecentlyViewed } = useRecentlyViewedStocks();
  const { toast } = useToast();
  const searchParams = useSearchParams();

  useEffect(() => {
    recordRecentlyViewed({ stockId, symbol, name: stockName, market });
  }, [stockId, symbol, stockName, market, recordRecentlyViewed]);

  // 스크리너 등에서 "알림 만들기" 바로가기로 들어온 경우 — 가격 알림 탭을 열고 잠깐 강조
  useEffect(() => {
    if (searchParams.get("openAlert") !== "1") return;
    setOrderTab("alert");
    setAlertHighlight(true);
    const t = setTimeout(() => setAlertHighlight(false), 1800);
    return () => clearTimeout(t);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 차트 이벤트 마커 클릭 → 이벤트 패널로 전환하고 해당 이벤트로 스크롤+강조
  const handleEventMarkerClick = useCallback((eventId: number) => {
    setEventsTab("events");
    setHighlightEventId(eventId);
  }, []);
  const showEvents = useCallback(() => setEventsTab("events"), []);

  // 미체결 주문을 차트 위 주문선으로 — 실전(brokerage)과 모의 지정가(ADR-074, 매칭 엔진 orders) 둘 다.
  const { isLoggedIn } = useAuth();
  const { data: brokerageAccount } = useBrokerageAccount();
  const brokerageConnected = !!brokerageAccount;
  const { data: activeOrders = [] } = useActiveBrokerageOrdersForSymbol(symbol, brokerageConnected);
  const cancelOrder = useCancelBrokerageOrder();
  const { data: paperOpenAll = [] } = usePaperOpenOrders(isLoggedIn);
  const paperOpen = useMemo(() => paperOpenAll.filter(o => o.stockId === stockId), [paperOpenAll, stockId]);
  const { cancel: cancelPaper } = usePaperOrder();
  // react-query 데이터가 그대로면 같은 배열을 넘긴다 — 새 배열이면 EChartsAdapter가 차트를 통째로 다시 만든다.
  // 주문선 id: 실전은 양수, 모의는 음수(-orderId) — 차트의 취소 콜백 하나로 두 계좌를 구분한다.
  const orderLines: OrderLine[] = useMemo(() => [
    ...activeOrders
      .filter(o => o.limitPrice != null)
      .map(o => ({ id: o.id, price: o.limitPrice as number, side: o.side, label: o.side === "BUY" ? "실전 매수 대기" : "실전 매도 대기" })),
    ...paperOpen
      .filter(o => o.limitPrice != null)
      .map(o => ({ id: -o.id, price: o.limitPrice as number, side: o.side, label: o.side === "BUY" ? "모의 매수 대기" : "모의 매도 대기" })),
  ], [activeOrders, paperOpen]);
  const handleCancelOrder = useCallback((orderId: number) => {
    cancelOrder.mutate(orderId, {
      onSuccess: () => toast({ type: "success", title: "주문 취소", message: "미체결 주문이 취소되었습니다." }),
      onError:   (e) => toast({ type: "error", title: "취소 실패", message: (e as Error).message }),
    });
  }, [cancelOrder, toast]);
  const handleCancelPaperOrder = useCallback((orderId: number) => {
    cancelPaper.mutate(orderId, {
      onSuccess: () => toast({ type: "success", title: "주문 취소", message: "모의투자 지정가 주문이 취소되었습니다." }),
      onError:   (e) => toast({ type: "error", title: "취소 실패", message: (e as Error).message }),
    });
  }, [cancelPaper, toast]);
  const handleCancelOrderLine = useCallback((id: number) => {
    if (id < 0) handleCancelPaperOrder(-id);
    else handleCancelOrder(id);
  }, [handleCancelOrder, handleCancelPaperOrder]);

  const { candles: dailyCandles, events } = useStockChart(stockId, "1d");
  const { price: livePrice } = useStockPrice(stockId);
  const quote = useQuotes([stockId])[stockId];

  const latestDaily = dailyCandles[dailyCandles.length - 1];
  const prevDaily = dailyCandles[dailyCandles.length - 2];
  const currentPrice = livePrice?.price ?? latestDaily?.close ?? 0;
  const prevClose = prevDaily?.close ?? null;
  const changeAmount = prevClose != null && currentPrice > 0 ? currentPrice - prevClose : null;
  const changeRate = changeAmount != null && prevClose ? (changeAmount / prevClose) * 100 : null;
  const up = (changeAmount ?? 0) >= 0;
  const sigma = dailySigma(dailyCandles.map(c => c.close));
  const today = new Date().toDateString();
  const todayEvents = events.filter(e => new Date(e.time * 1000).toDateString() === today).length;
  const tone = changeAmount == null ? undefined : up ? "text-up" : "text-down";

  const stats: TopStat[] = [
    { label: "현재가", value: currentPrice > 0 ? `${fmtNum(currentPrice)}원` : "—", tone, flash: livePrice?.price ?? null },
    {
      label: "전일 대비",
      value: changeAmount != null && changeRate != null ? `${up ? "▲" : "▼"}${fmtNum(Math.abs(changeAmount))} ${up ? "+" : ""}${changeRate.toFixed(2)}%` : "—",
      tone,
    },
    { label: "거래량", value: fmtShares(latestDaily?.volume ?? quote?.volume ?? null) },
    { label: "거래대금", value: quote?.amount ? fmtKrwCompact(quote.amount) : "—" },
    { label: "변동성 σ (20일)", value: sigma != null ? `${sigma.toFixed(2)}%` : "—" },
    { label: "오늘 이벤트", value: `${todayEvents}건`, tone: "text-dracula-purple" },
    { label: "시가총액", value: quote?.marketCap && !quote.isFundamentalsMocked ? fmtKrwCompact(quote.marketCap) : "—" },
  ];

  return (
    <TerminalPage
      left={
        <div className="flex flex-wrap items-center gap-2">
          <SymbolPill name={stockName} code={symbol} market={market} />
          <WatchlistAddButton stockId={stockId} />
        </div>
      }
      stats={stats}
      account={{ kind: "paper" }}
    >
      <PanelRow>
        {/* 주문 + 이벤트 */}
        <PanelCol className="flex-[0_1_310px]">
          <Panel
            tabs={[{ key: "order", label: "주문" }, { key: "conditional", label: "조건부" }, { key: "alert", label: "가격 알림" }]}
            active={orderTab}
            onTabChange={(k) => setOrderTab(k as OrderTab)}
            className={cn("transition-shadow duration-300", alertHighlight && "ring-2 ring-dracula-purple")}
          >
            {orderTab === "order" && (
              <OrderForm stock={{ id: stockId, symbol, name: stockName }} currentPrice={currentPrice} brokerageConnected={brokerageConnected} />
            )}
            {orderTab === "conditional" && (
              isLoggedIn ? (
                <>
                  <PaperConditionalPanel stockId={stockId} currentPrice={currentPrice} />
                  {brokerageConnected && (
                    <Notice tone="info">
                      여기서 거는 조건부 주문은 모의투자 전용입니다. 실전 계좌의 조건부 주문은{" "}
                      <Link href="/brokerage/conditional-orders">실전투자 · 조건부 주문</Link>에서 만듭니다.
                    </Notice>
                  )}
                </>
              ) : (
                <p className="m-0 py-8 text-center text-13 text-tm-muted">로그인하면 모의투자 조건부 주문(익절·손절·OCO)을 걸 수 있어요.</p>
              )
            )}
            {orderTab === "alert" && <AlertPanel stockId={stockId} symbol={symbol} bare />}
          </Panel>

          <Panel
            tabs={[{ key: "events", label: "이벤트" }, { key: "depth", label: "호가 깊이" }]}
            active={eventsTab}
            onTabChange={(k) => setEventsTab(k as EventsTab)}
            actions={["expand"]}
            bodyClassName="px-3 pb-2.5 pt-1"
          >
            {eventsTab === "events" ? (
              <EventTimeline stockId={stockId} bare highlightEventId={highlightEventId} onViewNews={() => setChartTab("news")} />
            ) : (
              <DepthPanel stockId={stockId} domestic={market === "KOSPI" || market === "KOSDAQ"} />
            )}
          </Panel>
        </PanelCol>

        {/* 호가 */}
        <Panel
          tabs={[{ key: "book", label: "호가" }, { key: "ticks", label: "체결" }, { key: "summary", label: "시장 요약" }]}
          active={bookTab}
          onTabChange={(k) => setBookTab(k as BookTab)}
          actions={["plus", "sliders", "expand"]}
          className="flex-[0_1_290px]"
          bodyClassName={bookTab === "book" ? "px-0 pb-3 pt-2.5" : undefined}
        >
          {bookTab === "book" && <OrderBook stockId={stockId} prevClose={prevClose} domestic={market === "KOSPI" || market === "KOSDAQ"} />}
          {bookTab === "ticks" && <RecentTrades stockId={stockId} />}
          {bookTab === "summary" && (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-2">
                <KV k="시가" v={latestDaily ? fmtNum(latestDaily.open) : "—"} />
                <KV k="고가" v={latestDaily ? fmtNum(latestDaily.high) : "—"} valueClassName="text-up" />
                <KV k="저가" v={latestDaily ? fmtNum(latestDaily.low) : "—"} valueClassName="text-down" />
                <KV k="전일 종가" v={prevClose != null ? fmtNum(prevClose) : "—"} />
                <KV k="거래량" v={fmtShares(latestDaily?.volume ?? null)} />
                <KV k="거래대금" v={quote?.amount ? fmtKrwCompact(quote.amount) : "—"} />
                <KV k="PER" v={quote?.per != null && !quote.isFundamentalsMocked ? quote.per.toFixed(1) : "—"} />
                <KV k="섹터" v={quote?.sector ?? "—"} mono={false} />
              </div>
              <StockScoreCard stockId={stockId} bare />
            </div>
          )}
        </Panel>

        {/* 차트 */}
        <ChartPanel
          stockId={stockId}
          symbol={symbol}
          stockName={stockName}
          currentPrice={currentPrice}
          dayChange={changeAmount}
          dayChangeRate={changeRate}
          tab={chartTab}
          onTabChange={setChartTab}
          onEventClick={handleEventMarkerClick}
          onShowEvents={showEvents}
          orderLines={orderLines}
          onCancelOrderLine={handleCancelOrderLine}
        />
      </PanelRow>

      <PositionsPanel
        symbol={symbol}
        stockId={stockId}
        brokerageConnected={brokerageConnected}
        activeOrders={activeOrders}
        onCancelOrder={handleCancelOrder}
        cancelPending={cancelOrder.isPending}
        paperOrders={paperOpen}
        onCancelPaperOrder={handleCancelPaperOrder}
        paperCancelPending={cancelPaper.isPending}
      />
    </TerminalPage>
  );
}
