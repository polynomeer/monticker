"use client";

import type { PortfolioTransaction } from "@monticker/types";
import { DataTable, IconBtn, type Column } from "@/components/terminal";
import { useDeleteTransaction } from "@/hooks/usePortfolio";
import { useStockMeta } from "@/components/portfolio/useStockMeta";
import { TX_TYPE_LABEL, fmtMoney, fmtQty } from "./labels";

/** 계좌의 거래(최근 순). 지우면 보유가 다시 계산된다 */
export function TransactionsTable({ accountId, rows }: { accountId: number; rows: PortfolioTransaction[] }) {
  const del = useDeleteTransaction(accountId);
  // 목록의 종목은 stockId만 온다 — 이름은 종목 정보로 채운다(같은 id는 한 번만 요청)
  const meta = useStockMeta(rows.map((t) => t.stockId));
  const columns: Column<PortfolioTransaction>[] = [
    { key: "date", header: "거래일", cell: (t) => <span className="num">{t.tradeDate}</span> },
    {
      key: "type", header: "구분",
      cell: (t) => <span className={t.type === "BUY" ? "text-up" : t.type === "SELL" ? "text-down" : "text-tm-soft"}>{TX_TYPE_LABEL[t.type]}</span>,
    },
    { key: "inst", header: "종목", cell: (t) => (t.stockId ? meta.get(t.stockId)?.name ?? `#${t.stockId}` : t.instrumentName ?? t.instrumentCode ?? "—") },
    { key: "qty", header: "수량", align: "right", cell: (t) => <span className="num">{fmtQty(t.quantity)}</span> },
    { key: "price", header: "단가", align: "right", cell: (t) => <span className="num">{fmtMoney(t.price, t.currency)}</span> },
    { key: "amount", header: "금액", align: "right", cell: (t) => <span className="num">{fmtMoney(t.amount, t.currency)}</span> },
    { key: "cost", header: "수수료·세금", align: "right", cell: (t) => <span className="num text-tm-soft">{t.fee + t.tax === 0 ? "—" : fmtMoney(t.fee + t.tax, t.currency)}</span> },
    { key: "memo", header: "메모", cell: (t) => <span className="text-xs text-tm-muted">{t.memo ?? ""}</span> },
    {
      key: "del", header: <span className="sr-only">삭제</span>, align: "right",
      cell: (t) => (
        <IconBtn
          name="trash" label={`${t.tradeDate} ${TX_TYPE_LABEL[t.type]} 거래 삭제`} size={26} iconSize={14}
          disabled={del.isPending}
          onClick={() => { if (confirm("이 거래를 지울까요? 보유가 다시 계산됩니다.")) del.mutate(t.id); }}
        />
      ),
    },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(t) => t.id} minWidth={820} empty="아직 거래가 없습니다." />;
}
