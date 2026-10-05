"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import type { BrokerageOrderResponse, ConditionalOrderResponse } from "@monticker/types";
import { ChgNum, DataTable, Panel, Pill, StockCell, dirClass, fmtNum, fmtSigned, type Column } from "@/components/terminal";
import { usePaperHistory, usePaperPortfolio, type Holding, type TradeHistory } from "@/hooks/usePaperTrade";
import { useConditionalOrders } from "@/hooks/useBrokerage";
import { useAuth } from "@/hooks/useAuth";
import { authFetch } from "@/services/api";
import { cn } from "@/lib/utils";

interface PaperSettlement {
  id: number;
  tradeId: number;
  side: "BUY" | "SELL";
  quantity: number;
  fillPrice: number;
  netAmount: number;
  status: "PENDING" | "SETTLED" | "FAILED";
  settleDate: string;
}

type Tab = "holdings" | "open" | "conditional" | "fills" | "settlement";

interface Props {
  symbol: string;
  /** 실전 계좌 연동 여부 — 미체결·조건부 주문은 실전 계좌에만 있다(모의투자는 시장가 즉시 체결) */
  brokerageConnected: boolean;
  activeOrders: BrokerageOrderResponse[];
  onCancelOrder: (orderId: number) => void;
  cancelPending: boolean;
}

const SIDE = (s: string) => (s === "BUY" ? <span className="text-up">매수</span> : <span className="text-down">매도</span>);
const when = (iso: string) => new Date(iso).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });

const ORDER_STATUS: Record<string, string> = {
  PENDING_SUBMIT: "제출 중", SUBMITTED: "접수", UNKNOWN: "결과 확인 중", PARTIALLY_FILLED: "부분 체결",
  FILLED: "체결", CANCELLED: "취소", REJECTED: "거부",
};
const TRIGGER_LABEL: Record<string, string> = { STOP_LOSS: "손절", TAKE_PROFIT: "익절", PRICE_ABOVE: "가격 이상", PRICE_BELOW: "가격 이하" };
const COND_STATUS: Record<string, { label: string; tone: "green" | "muted" | "orange" | "red" | "cyan" }> = {
  ACTIVE: { label: "감시 중", tone: "cyan" },
  TRIGGERED: { label: "발동", tone: "orange" },
  EXECUTED: { label: "체결", tone: "green" },
  CANCELLED: { label: "취소", tone: "muted" },
  EXPIRED: { label: "만료", tone: "muted" },
  FAILED: { label: "실패", tone: "red" },
};

/** 시안 Main 하단 패널 — 보유 종목 / 미체결 / 조건부 / 체결 내역 / 정산 대기 */
export default function PositionsPanel({ symbol, brokerageConnected, activeOrders, onCancelOrder, cancelPending }: Props) {
  const [tab, setTab] = useState<Tab>("holdings");
  const { isLoggedIn } = useAuth();

  const tabs = [
    { key: "holdings", label: "보유 종목" },
    { key: "open", label: activeOrders.length ? `미체결 주문 ${activeOrders.length}` : "미체결 주문" },
    { key: "conditional", label: "조건부 주문" },
    { key: "fills", label: "체결 내역" },
    { key: "settlement", label: "정산 대기" },
  ];

  return (
    <Panel tabs={tabs} active={tab} onTabChange={(k) => setTab(k as Tab)} actions={["plus", "download", "expand"]} bodyClassName="px-1.5 pb-1.5 pt-1">
      {!isLoggedIn ? (
        <p className="m-0 py-8 text-center text-13 text-tm-muted">로그인이 필요합니다.</p>
      ) : tab === "holdings" ? (
        <Holdings />
      ) : tab === "open" ? (
        <OpenOrders connected={brokerageConnected} symbol={symbol} orders={activeOrders} onCancel={onCancelOrder} cancelPending={cancelPending} />
      ) : tab === "conditional" ? (
        <Conditional connected={brokerageConnected} symbol={symbol} />
      ) : tab === "fills" ? (
        <Fills />
      ) : (
        <Settlements />
      )}
    </Panel>
  );
}

function Holdings() {
  const { data, isLoading, isError } = usePaperPortfolio();
  const cols: Column<Holding>[] = [
    { key: "name", header: "종목", cell: (h) => <StockCell name={h.name} code={h.symbol} href={`/stocks/${h.symbol}`} /> },
    { key: "qty", header: "수량", align: "right", cell: (h) => <span className="num">{fmtNum(h.quantity)}</span> },
    { key: "avg", header: "평균단가", align: "right", cell: (h) => <span className="num">{fmtNum(h.avgPrice)}</span> },
    { key: "cur", header: "현재가", align: "right", cell: (h) => <span className="num">{fmtNum(h.currentPrice)}</span> },
    { key: "val", header: "평가금액", align: "right", cell: (h) => <span className="num">{fmtNum(h.value)}</span> },
    { key: "pnl", header: "평가손익", align: "right", cell: (h) => <span className={cn("num", dirClass(h.pnl))}>{fmtSigned(h.pnl)}</span> },
    { key: "rate", header: "수익률", align: "right", cell: (h) => <ChgNum value={h.pnlRate} /> },
    // 시안의 "최근 이벤트"·"진입 경로" — 보유 종목별 이벤트·진입 출처 데이터가 아직 없다
    { key: "ev", header: "최근 이벤트", cell: () => <span className="text-tm-muted">—</span> },
    { key: "route", header: "진입 경로", cell: () => <span className="text-tm-muted">—</span> },
  ];
  if (isLoading) return <div className="m-1.5 h-24 animate-pulse rounded-lg bg-tm-inner" />;
  if (isError) return <p className="m-0 py-8 text-center text-13 text-tm-muted">모의투자 포트폴리오를 불러오지 못했습니다.</p>;
  return <DataTable columns={cols} rows={data?.holdings ?? []} rowKey={(h) => h.stockId} minWidth={900} empty="모의투자 보유 종목이 없습니다." />;
}

function OpenOrders({ connected, symbol, orders, onCancel, cancelPending }: { connected: boolean; symbol: string; orders: BrokerageOrderResponse[]; onCancel: (id: number) => void; cancelPending: boolean }) {
  if (!connected) {
    return (
      <p className="m-0 py-8 text-center text-13 text-tm-muted">
        모의투자는 시장가 즉시 체결이라 미체결 주문이 없습니다. 실전 지정가 주문은 <Link href="/brokerage/connect">실전 계좌 연동</Link> 후 표시됩니다.
      </p>
    );
  }
  const cols: Column<BrokerageOrderResponse>[] = [
    { key: "acc", header: "계좌", cell: () => <Pill tone="orange">실전</Pill> },
    { key: "side", header: "구분", cell: (o) => SIDE(o.side) },
    { key: "type", header: "유형", cell: (o) => (o.orderType === "LIMIT" ? "지정가" : "시장가") },
    { key: "qty", header: "수량", align: "right", cell: (o) => <span className="num">{fmtNum(o.filledQty)} / {fmtNum(o.quantity)}</span> },
    { key: "px", header: "지정가", align: "right", cell: (o) => <span className="num">{o.limitPrice != null ? fmtNum(o.limitPrice) : "—"}</span> },
    { key: "st", header: "상태", cell: (o) => <span className="text-tm-soft">{ORDER_STATUS[o.status] ?? o.status}</span> },
    { key: "at", header: "주문 시각", cell: (o) => <span className="num text-tm-muted">{when(o.submittedAt)}</span> },
    {
      key: "x", header: "", align: "right",
      cell: (o) => (
        <button
          type="button"
          disabled={cancelPending}
          onClick={() => { if (window.confirm(`${symbol} ${o.side === "BUY" ? "매수" : "매도"} 주문을 취소할까요?`)) onCancel(o.id); }}
          className="text-xs text-[#ff8a8a] hover:underline disabled:opacity-40"
        >
          취소
        </button>
      ),
    },
  ];
  return <DataTable columns={cols} rows={orders} rowKey={(o) => o.id} minWidth={760} empty={`${symbol} 미체결 실전 주문이 없습니다.`} />;
}

function Conditional({ connected, symbol }: { connected: boolean; symbol: string }) {
  const { data, isLoading } = useConditionalOrders(0, connected);
  if (!connected) {
    return (
      <p className="m-0 py-8 text-center text-13 text-tm-muted">
        조건부 주문(익절·손절·OCO)은 실전 계좌에서만 쓸 수 있습니다. <Link href="/brokerage/connect">실전 계좌 연동 →</Link>
      </p>
    );
  }
  const rows = (data?.content ?? []).filter((c) => c.symbol === symbol);
  const cols: Column<ConditionalOrderResponse>[] = [
    { key: "side", header: "구분", cell: (c) => SIDE(c.side) },
    { key: "trig", header: "조건", cell: (c) => <span>{TRIGGER_LABEL[c.triggerType] ?? c.triggerType} <span className="num">{fmtNum(c.triggerPrice)}</span></span> },
    { key: "ord", header: "주문", cell: (c) => (c.orderType === "LIMIT" ? <span>지정가 <span className="num">{fmtNum(c.limitPrice)}</span></span> : "시장가") },
    { key: "qty", header: "수량", align: "right", cell: (c) => <span className="num">{fmtNum(c.quantity)}</span> },
    { key: "oco", header: "OCO", cell: (c) => (c.ocoGroupId ? <Pill tone="purple">OCO</Pill> : <span className="text-tm-muted">—</span>) },
    { key: "st", header: "상태", cell: (c) => { const m = COND_STATUS[c.status] ?? { label: c.status, tone: "muted" as const }; return <Pill tone={m.tone}>{m.label}</Pill>; } },
    { key: "at", header: "등록", cell: (c) => <span className="num text-tm-muted">{when(c.createdAt)}</span> },
  ];
  if (isLoading) return <div className="m-1.5 h-24 animate-pulse rounded-lg bg-tm-inner" />;
  return (
    <>
      <DataTable columns={cols} rows={rows} rowKey={(c) => c.id} minWidth={720} empty={`${symbol} 조건부 주문이 없습니다.`} />
      <Link href="/brokerage/conditional-orders" className="px-2.5 py-2 text-xs">조건부 주문 전체 보기 →</Link>
    </>
  );
}

function Fills() {
  const { data = [], isLoading } = usePaperHistory();
  const cols: Column<TradeHistory>[] = [
    { key: "at", header: "체결 시각", cell: (t) => <span className="num text-tm-muted">{when(t.tradedAt)}</span> },
    { key: "name", header: "종목", cell: (t) => <StockCell name={t.name} code={t.symbol} href={`/stocks/${t.symbol}`} /> },
    { key: "side", header: "구분", cell: (t) => SIDE(t.side) },
    { key: "qty", header: "수량", align: "right", cell: (t) => <span className="num">{fmtNum(t.quantity)}</span> },
    { key: "px", header: "체결가", align: "right", cell: (t) => <span className="num">{fmtNum(t.price)}</span> },
    { key: "amt", header: "체결금액", align: "right", cell: (t) => <span className="num">{fmtNum(t.amount)}</span> },
    { key: "acc", header: "계좌", cell: () => <Pill tone="yellow">모의</Pill> },
  ];
  if (isLoading) return <div className="m-1.5 h-24 animate-pulse rounded-lg bg-tm-inner" />;
  return <DataTable columns={cols} rows={data.slice(0, 50)} rowKey={(t) => t.id} minWidth={760} empty="모의투자 체결 내역이 없습니다." />;
}

function Settlements() {
  const { data = [], isLoading, isError } = useQuery<PaperSettlement[]>({
    queryKey: ["settlement", "paper", "pending", "stock-terminal"],
    queryFn: async () => {
      const res = await authFetch("/api/settlement/paper/pending");
      if (!res.ok) throw new Error("조회 실패");
      const json = await res.json();
      return Array.isArray(json) ? json : json?.content ?? [];
    },
  });
  const cols: Column<PaperSettlement>[] = [
    { key: "trade", header: "거래", cell: (s) => <span className="num text-tm-muted">#{s.tradeId}</span> },
    { key: "side", header: "구분", cell: (s) => SIDE(s.side) },
    { key: "qty", header: "수량", align: "right", cell: (s) => <span className="num">{fmtNum(s.quantity)}</span> },
    { key: "px", header: "체결가", align: "right", cell: (s) => <span className="num">{fmtNum(s.fillPrice)}</span> },
    { key: "net", header: "정산 금액", align: "right", cell: (s) => <span className={cn("num", s.side === "BUY" ? "text-down" : "text-up")}>{s.side === "BUY" ? "-" : "+"}{fmtNum(s.netAmount)}</span> },
    { key: "date", header: "정산 예정일", cell: (s) => <span className="num">{new Date(s.settleDate).toLocaleDateString("ko-KR")}</span> },
    { key: "st", header: "상태", cell: () => <Pill tone="orange">대기 중</Pill> },
  ];
  if (isLoading) return <div className="m-1.5 h-24 animate-pulse rounded-lg bg-tm-inner" />;
  if (isError) return <p className="m-0 py-8 text-center text-13 text-tm-muted">정산 내역을 불러오지 못했습니다.</p>;
  return (
    <>
      <DataTable columns={cols} rows={data} rowKey={(s) => s.id} minWidth={720} empty="정산 대기 중인 모의투자 거래가 없습니다. (체결 후 T+2 영업일 정산)" />
      <Link href="/settlement" className="px-2.5 py-2 text-xs">정산 내역 전체 보기 →</Link>
    </>
  );
}
