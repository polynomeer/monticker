"use client";

import { useEffect, useMemo, useState } from "react";
import { getAccessToken } from "@/services/auth";
import { usePaperPortfolio, usePaperHistory, usePaperTrade, type Holding } from "@/hooks/usePaperTrade";
import { useToast } from "@/hooks/useToast";
import TradeModal from "@/components/paper/TradeModal";
import RiskPanel from "@/components/portfolio/RiskPanel";
import { HoldingsTable } from "@/components/portfolio/HoldingsTable";
import { TradeHistoryTable } from "@/components/portfolio/TradeHistoryTable";
import { QuickTradePanel } from "@/components/portfolio/QuickTradePanel";
import { AvgPriceOverlay, PnlContribution, SectorConcentration } from "@/components/portfolio/Insights";
import { LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import { useStockMeta } from "@/components/portfolio/useStockMeta";
import { downloadCsv } from "@/components/portfolio/csv";
import { fmtDateTime } from "@/components/portfolio/format";
import { Btn, Panel, PanelRow, TerminalPage, dirClass, fmtNum, fmtPct, fmtSigned, type TopStat } from "@/components/terminal";

export default function PortfolioPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [sellModal, setSellModal] = useState<Holding | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [holdTab, setHoldTab] = useState("보유 종목");

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: portfolio, isLoading } = usePaperPortfolio();
  const { data: history = [] } = usePaperHistory();
  const { reset } = usePaperTrade();
  const { toast } = useToast();

  const holdings = useMemo(() => portfolio?.holdings ?? [], [portfolio]);
  const meta = useStockMeta(holdings.map((h) => h.stockId));
  const selected = holdings.find((h) => h.stockId === selectedId) ?? holdings[0] ?? null;

  const sectorValue = useMemo(() => {
    const m = new Map<string, number>();
    holdings.forEach((h) => {
      const s = meta.get(h.stockId)?.sector;
      if (s) m.set(s, (m.get(s) ?? 0) + h.value);
    });
    return m;
  }, [holdings, meta]);

  const handleReset = async () => {
    if (!confirm("계좌를 초기화하면 모든 거래 내역이 삭제됩니다.")) return;
    try {
      await reset.mutateAsync();
    } catch (e) {
      toast({ type: "error", title: "초기화 실패", message: e instanceof Error ? e.message : undefined });
    }
  };

  const stockValue = holdings.reduce((a, h) => a + h.value, 0);
  const stats: TopStat[] = portfolio
    ? [
        { label: "총 평가금액", value: `${fmtNum(portfolio.totalValue)}원` },
        { label: "주식 평가액", value: `${fmtNum(stockValue)}원` },
        { label: "총 손익", value: fmtSigned(portfolio.totalPnl), tone: dirClass(portfolio.totalPnl) },
        { label: "수익률", value: fmtPct(portfolio.totalPnlRate), tone: dirClass(portfolio.totalPnlRate) },
        { label: "가용 현금", value: `${fmtNum(portfolio.cash)}원` },
        // 벤치마크(KOSPI) 대비 성과는 지수 수익률 API가 아직 없다
        { label: "벤치마크 대비", value: "—", tone: "text-tm-muted" },
      ]
    : [];
  const account = { kind: "paper" as const };

  const exportHoldings = () =>
    downloadCsv(
      "portfolio-holdings.csv",
      ["종목", "코드", "수량", "평균단가", "현재가", "평가금액", "평가손익", "수익률(%)", "섹터"],
      holdings.map((h) => [h.name, h.symbol, h.quantity, h.avgPrice, h.currentPrice, h.value, h.pnl, h.pnlRate.toFixed(2), meta.get(h.stockId)?.sector ?? ""]),
    );
  const exportHistory = () =>
    downloadCsv(
      "portfolio-history.csv",
      ["시각", "종목", "구분", "수량", "체결가", "금액"],
      history.map((h) => [fmtDateTime(h.tradedAt), h.name ?? h.symbol, h.side === "BUY" ? "매수" : "매도", h.quantity, h.price, h.amount ?? h.price * h.quantity]),
    );

  if (!isLoggedIn) {
    return (
      <TerminalPage title="모의 포트폴리오" crumb="포트폴리오 인사이트">
        <LoginRequired message="모의 투자를 이용하려면 로그인이 필요합니다." />
      </TerminalPage>
    );
  }

  if (isLoading) {
    return (
      <TerminalPage title="모의 포트폴리오" crumb="포트폴리오 인사이트" account={account}>
        <PanelRow>
          <Skeleton className="h-72 flex-[999_1_700px]" />
          <Skeleton className="h-72 flex-[0_1_300px]" />
        </PanelRow>
      </TerminalPage>
    );
  }

  return (
    <TerminalPage title="모의 포트폴리오" crumb="포트폴리오 인사이트" stats={stats} account={account}>
      <PanelRow>
        <Panel
          tabs={["보유 종목", "거래 내역"]}
          active={holdTab}
          onTabChange={setHoldTab}
          actions={["download", "expand"]}
          onAction={(a) => a === "download" && (holdTab === "보유 종목" ? exportHoldings() : exportHistory())}
          right={
            <Btn kind="ghost" size="sm" onClick={handleReset} disabled={reset.isPending}>
              초기화
            </Btn>
          }
          className="flex-[999_1_700px]"
          bodyClassName="px-1.5 pb-1.5 pt-1"
          render={(k) =>
            k === "보유 종목" ? (
              <HoldingsTable
                holdings={holdings}
                meta={meta}
                selectedId={selected?.stockId}
                onSelect={(h) => setSelectedId(h.stockId)}
                onSell={setSellModal}
              />
            ) : (
              <TradeHistoryTable history={history} />
            )
          }
        />
        <Panel tabs={["빠른 매수"]} actions={[]} className="flex-[0_1_300px]">
          <QuickTradePanel portfolio={portfolio} meta={meta} sectorValue={sectorValue} />
        </Panel>
      </PanelRow>

      <PanelRow>
        <Panel tabs={["손익 기여도"]} actions={["expand"]} className="flex-[1_1_360px]">
          <PnlContribution holdings={holdings} />
        </Panel>
        <Panel tabs={["위험 집중도"]} actions={["expand"]} className="flex-[1_1_360px]">
          <SectorConcentration holdings={holdings} cash={portfolio?.cash ?? 0} meta={meta} />
        </Panel>
        <Panel
          tabs={["평균단가 오버레이"]}
          actions={["expand"]}
          className="flex-[1_1_420px]"
          right={
            holdings.length > 0 && (
              <select
                aria-label="평균단가를 볼 종목"
                value={selected?.stockId ?? ""}
                onChange={(e) => setSelectedId(Number(e.target.value))}
                className="h-[30px] rounded-md bg-tm-raised px-2 text-13 text-dracula-fg outline-none [&>option]:bg-tm-panel"
              >
                {holdings.map((h) => <option key={h.stockId} value={h.stockId}>{h.name}</option>)}
              </select>
            )
          }
        >
          <AvgPriceOverlay holding={selected} />
        </Panel>
      </PanelRow>

      <Panel tabs={["거래 내역"]} actions={["download"]} onAction={(a) => a === "download" && exportHistory()} bodyClassName="px-1.5 pb-1.5 pt-1">
        <TradeHistoryTable history={history} />
      </Panel>

      <RiskPanel />

      {sellModal && (
        <TradeModal
          stock={{ id: sellModal.stockId, symbol: sellModal.symbol, name: sellModal.name }}
          currentPrice={sellModal.currentPrice}
          side="SELL"
          maxQuantity={sellModal.quantity}
          onClose={() => setSellModal(null)}
        />
      )}
    </TerminalPage>
  );
}
