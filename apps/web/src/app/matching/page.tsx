"use client";

import { useEffect, useState } from "react";
import OrderProposalCard from "@/components/ai/OrderProposalCard";
import { getAccessToken } from "@/services/auth";
import { ClobBook } from "@/components/matching/ClobBook";
import RecentTrades from "@/components/stock/RecentTrades";
import { OrderForm } from "@/components/matching/OrderForm";
import { OrdersPanel } from "@/components/matching/OrdersPanel";
import { STOCKS, useActiveOrders, useMyFills, useOrderbook } from "@/components/matching/data";
import { useStockMeta } from "@/components/portfolio/useStockMeta";
import { fmtTime } from "@/components/portfolio/format";
import { Panel, PanelCol, PanelRow, TerminalPage, fmtNum, type TopStat } from "@/components/terminal";

export default function MatchingPage() {
  const [stockId, setStockId] = useState(2);
  const [presetSide, setPresetSide] = useState<"BUY" | "SELL" | undefined>(undefined);
  const [tapeTab, setTapeTab] = useState<"market" | "mine">("market");
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: book, isLoading: bookLoading } = useOrderbook(stockId);
  const { data: orders = [] } = useActiveOrders();
  const { data: fills = [] } = useMyFills();

  const meta = useStockMeta([...orders.map((o) => o.stockId), ...fills.map((f) => f.stockId)]);
  const stockName = (id: number) => STOCKS.find((s) => s.id === id)?.label ?? meta.get(id)?.name ?? `종목 #${id}`;

  const today = new Date().toDateString();
  const todayFills = fills.filter((f) => new Date(f.filledAt).toDateString() === today).length;
  const tape = fills.filter((f) => f.stockId === stockId).slice(0, 14);

  const stats: TopStat[] = [
    { label: "엔진", value: "가격·시간 우선" },
    { label: "미체결", value: `${orders.length}건` },
    { label: "오늘 체결", value: `${todayFills}건` },
    // 체결가 vs 주문 시점 최우선 호가 기록이 없어 평균 슬리피지·엔진 지연은 아직 집계하지 않는다
    { label: "평균 슬리피지", value: "—", tone: "text-tm-muted" },
    { label: "지연", value: "—", tone: "text-tm-muted" },
  ];

  return (
    <TerminalPage title="체결 엔진" crumb="모의투자" stats={isLoggedIn ? stats : stats.slice(0, 1)}>
      <PanelRow>
        <PanelCol className="flex-[0_1_300px]">
          <Panel tabs={["주문 입력"]} actions={[]}>
            <OrderForm stockId={stockId} setStockId={setStockId} presetSide={presetSide} book={book} />
          </Panel>
          <OrderProposalCard stockId={stockId} onApprove={setPresetSide} />
        </PanelCol>
        <Panel tabs={["오더북 (CLOB)"]} actions={["expand"]} className="flex-[2_1_420px]" bodyClassName="px-0 py-2.5">
          <ClobBook book={book} loading={bookLoading} myOrders={orders} />
        </Panel>
        <Panel
          tabs={[{ key: "market", label: "시장 체결" }, { key: "mine", label: "내 체결" }]}
          active={tapeTab}
          onTabChange={(k) => setTapeTab(k as "market" | "mine")}
          actions={["expand"]}
          className="flex-[1_1_260px]"
          bodyClassName="px-0 py-2"
        >
          {/* 시장 체결: 이 종목의 실시간 체결 틱(서버 링 버퍼). 내 체결: 이 종목의 내 모의 체결 */}
          {tapeTab === "market" ? (
            <RecentTrades stockId={stockId} limit={50} maxRows={30} />
          ) : tape.length === 0 ? (
            <p className="m-0 px-2.5 py-8 text-center text-13 text-tm-muted">이 종목의 내 체결이 아직 없습니다.</p>
          ) : (
            <ul className="m-0 list-none p-0" aria-label="체결 테이프">
              {tape.map((f) => (
                <li key={f.id} className="grid grid-cols-[62px_1fr_1fr] px-2.5 py-1 text-xs">
                  <span className="num text-tm-muted">{fmtTime(f.filledAt)}</span>
                  <span className={`num ${f.side === "BUY" ? "text-up" : "text-down"}`}>{fmtNum(f.fillPrice)}</span>
                  <span className="num text-right">{fmtNum(f.quantity)}</span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </PanelRow>

      <OrdersPanel orders={orders} fills={fills} stockName={stockName} />
    </TerminalPage>
  );
}
