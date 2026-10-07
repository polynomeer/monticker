"use client";

import type { TradeHistory } from "@/hooks/usePaperTrade";
import { Chip, DataTable, Num, fmtNum, type Column } from "@/components/terminal";
import { emotionLabel } from "@/components/wallet/emotions";
import { useTradeEmotions } from "@/components/wallet/useTradeEmotions";
import { fmtDateTime } from "./format";

/**
 * 시안 Portfolio "거래 내역" 표. 감정은 거래별 태그 API로 채운다.
 * "경로"(직접/Watch Rule) 칸은 거래 내역 응답에 출처가 없어 아직 "—"로 둔다.
 */
export function TradeHistoryTable({ history, limit = 20 }: { history: TradeHistory[]; limit?: number }) {
  const rows = history.slice(0, limit);
  const emotions = useTradeEmotions(rows.map((r) => r.id));
  const cols: Column<TradeHistory>[] = [
    { key: "time", header: "시각", cell: (h) => <Num className="text-tm-muted">{fmtDateTime(h.tradedAt)}</Num> },
    { key: "name", header: "종목", cell: (h) => h.name ?? h.symbol },
    { key: "side", header: "구분", cell: (h) => <span className={h.side === "BUY" ? "text-up" : "text-down"}>{h.side === "BUY" ? "매수" : "매도"}</span> },
    { key: "qty", header: "수량", align: "right", cell: (h) => <Num>{fmtNum(h.quantity)}</Num> },
    { key: "price", header: "체결가", align: "right", cell: (h) => <Num>{fmtNum(h.price)}</Num> },
    { key: "amount", header: "금액", align: "right", cell: (h) => <Num>{fmtNum(h.amount ?? h.price * h.quantity)}</Num> },
    {
      key: "emotion",
      header: "감정",
      cell: (h) => {
        const l = emotionLabel(emotions.get(h.id));
        return l ? <Chip className="h-[22px]">{l}</Chip> : <span className="text-tm-muted">—</span>;
      },
    },
    { key: "route", header: "경로", cell: () => <span className="text-tm-muted" title="거래 출처(직접·Watch Rule)는 준비 중입니다">—</span> },
  ];
  return <DataTable columns={cols} rows={rows} rowKey={(h) => h.id} minWidth={800} empty="거래 내역이 없습니다." />;
}
