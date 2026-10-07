"use client";

import type { Holding } from "@/hooks/usePaperTrade";
import { Bar, Btn, ChgNum, DataTable, Num, StockCell, dirClass, fmtNum, fmtSigned, type Column } from "@/components/terminal";
import type { StockMeta } from "./useStockMeta";

interface Props {
  holdings: Holding[];
  meta: Map<number, StockMeta>;
  selectedId?: number | null;
  onSelect?: (h: Holding) => void;
  onSell: (h: Holding) => void;
}

/** 시안 Portfolio "보유 종목" 표 — 비중은 주식 평가액 합계 대비. */
export function HoldingsTable({ holdings, meta, selectedId, onSelect, onSell }: Props) {
  const total = holdings.reduce((a, h) => a + h.value, 0) || 1;
  const cols: Column<Holding>[] = [
    { key: "stock", header: "종목", cell: (h) => <StockCell name={h.name} code={h.symbol} href={`/stocks/${h.symbol}`} /> },
    { key: "qty", header: "수량", align: "right", cell: (h) => <Num>{fmtNum(h.quantity)}</Num> },
    { key: "avg", header: "평균단가", align: "right", cell: (h) => <Num>{fmtNum(h.avgPrice)}</Num> },
    { key: "price", header: "현재가", align: "right", cell: (h) => <Num>{fmtNum(h.currentPrice)}</Num> },
    { key: "value", header: "평가금액", align: "right", cell: (h) => <Num>{fmtNum(h.value)}</Num> },
    { key: "pnl", header: "평가손익", align: "right", cell: (h) => <Num className={dirClass(h.pnl)}>{fmtSigned(h.pnl)}</Num> },
    { key: "rate", header: "수익률", align: "right", cell: (h) => <ChgNum value={h.pnlRate} /> },
    {
      key: "weight",
      header: "비중",
      cell: (h) => {
        const w = (h.value / total) * 100;
        return (
          <div className="flex min-w-[110px] items-center gap-2">
            <div className="flex-1"><Bar pct={w} h={5} /></div>
            <span className="num text-xs">{w.toFixed(1)}%</span>
          </div>
        );
      },
    },
    { key: "sector", header: "섹터", cell: (h) => <span className="text-tm-soft">{meta.get(h.stockId)?.sector ?? "—"}</span> },
    {
      key: "act",
      header: <span className="sr-only">주문</span>,
      align: "right",
      cell: (h) => (
        <Btn
          kind="ghost"
          size="sm"
          aria-label={`${h.name} 매도`}
          onClick={(e) => { e.stopPropagation(); onSell(h); }}
        >
          매도
        </Btn>
      ),
    },
  ];
  const sel = selectedId == null ? undefined : holdings.findIndex((h) => h.stockId === selectedId);
  return (
    <DataTable
      columns={cols}
      rows={holdings}
      rowKey={(h) => h.stockId}
      minWidth={920}
      selectedIndex={sel != null && sel >= 0 ? sel : undefined}
      onRowClick={onSelect}
      empty="보유 종목이 없습니다."
    />
  );
}
