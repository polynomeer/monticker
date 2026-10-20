"use client";

import type { PortfolioHolding } from "@monticker/types";
import { DataTable, Pill, StockCell, dirClass, fmtPct, type Column } from "@/components/terminal";
import { EmptyNote } from "@/components/portfolio/PaperStates";
import { fmtMoney, fmtQty } from "./labels";

/** 통합 보유 — 같은 종목·통화를 계좌 사이에 합친 행. 시세 없는 상품(펀드·채권 등)은 평가 칸이 "—"다 */
export function AssetHoldingsTable({ holdings, showAccounts = true }: { holdings: PortfolioHolding[]; showAccounts?: boolean }) {
  // 빈 표의 안내는 넓은(minWidth) 표 가운데에 놓여 좁은 화면에서는 화면 밖으로 밀린다
  if (holdings.length === 0) return <EmptyNote>보유 종목이 없습니다.</EmptyNote>;
  const columns: Column<PortfolioHolding>[] = [
    {
      key: "name", header: "종목",
      cell: (h) => (
        <div className="flex items-center gap-2">
          <StockCell name={h.name ?? h.symbol ?? h.instrumentCode ?? "—"} code={h.symbol ?? h.instrumentCode ?? undefined} href={h.stockId && h.symbol ? `/stocks/${h.symbol}` : undefined} />
          {h.needsReview && (
            <span title="가진 것보다 많이 판 기록이 있습니다. 앞 구간 거래가 빠졌을 수 있습니다.">
              <Pill tone="orange">확인 필요</Pill>
            </span>
          )}
        </div>
      ),
    },
    { key: "qty", header: "수량", align: "right", cell: (h) => <span className="num">{fmtQty(h.quantity)}</span> },
    { key: "avg", header: "평균단가", align: "right", cell: (h) => <span className="num">{fmtMoney(h.avgCost, h.currency)}</span> },
    { key: "price", header: "현재가", align: "right", cell: (h) => <span className="num">{fmtMoney(h.currentPrice, h.currency)}</span> },
    { key: "value", header: "평가금액", align: "right", cell: (h) => <span className="num">{fmtMoney(h.marketValue, h.currency)}</span> },
    {
      key: "pnl", header: "평가손익", align: "right",
      cell: (h) => (
        <span className={`num ${dirClass(h.unrealizedPnl)}`}>
          {h.unrealizedPnl == null ? "—" : `${h.unrealizedPnl > 0 ? "+" : ""}${fmtMoney(h.unrealizedPnl, h.currency)}`}
          <span className="ml-1.5 text-2xs">{fmtPct(h.unrealizedPnlRate)}</span>
        </span>
      ),
    },
    { key: "weight", header: "비중", align: "right", cell: (h) => <span className="num text-tm-soft">{h.weight == null ? "—" : `${h.weight.toFixed(1)}%`}</span> },
    { key: "realized", header: "실현손익", align: "right", cell: (h) => <span className={`num ${dirClass(h.realizedPnl)}`}>{h.realizedPnl === 0 ? "—" : fmtMoney(h.realizedPnl, h.currency)}</span> },
  ];
  if (showAccounts) {
    columns.push({
      key: "accounts", header: "계좌",
      cell: (h) => <span className="text-xs text-tm-soft">{h.accounts.map((a) => `${a.accountName} ${fmtQty(a.quantity)}`).join(" · ")}</span>,
    });
  }
  return (
    <DataTable
      columns={columns}
      rows={holdings}
      rowKey={(h) => `${h.stockId ?? h.instrumentCode}-${h.currency}`}
      minWidth={900}
    />
  );
}
