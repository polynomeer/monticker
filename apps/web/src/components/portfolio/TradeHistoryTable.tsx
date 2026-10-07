"use client";

import Link from "next/link";
import type { TradeHistory } from "@/hooks/usePaperTrade";
import { Chip, DataTable, Num, Pill, fmtNum, type Column } from "@/components/terminal";
import { emotionLabel } from "@/components/wallet/emotions";
import { useTradeEmotions } from "@/components/wallet/useTradeEmotions";
import { fmtDateTime } from "./format";

/** 거래 경로 — 서버가 체결을 만든 주문의 멱등 키로 판정한다(직접·Watch Rule·조건부). */
function RouteCell({ h }: { h: TradeHistory }) {
  const limit = h.orderType === "LIMIT" ? " · 지정가" : "";
  if (h.source === "WATCH_RULE") {
    return (
      <Link href="/watch-rules" title={h.watchRuleId ? `Watch Rule #${h.watchRuleId}` : undefined} className="no-underline">
        <Pill tone="purple">Watch Rule{h.watchRuleId ? ` #${h.watchRuleId}` : ""}</Pill>
      </Link>
    );
  }
  if (h.source === "CONDITIONAL") return <Pill tone="cyan">조건부{limit}</Pill>;
  if (h.source === "MANUAL") return <span className="text-tm-soft">직접{limit}</span>;
  return <span className="text-tm-muted">—</span>;
}

/** 시안 Portfolio "거래 내역" 표. 감정은 거래별 태그 API로 채운다. */
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
    { key: "route", header: "경로", cell: (h) => <RouteCell h={h} /> },
  ];
  return <DataTable columns={cols} rows={rows} rowKey={(h) => h.id} minWidth={800} empty="거래 내역이 없습니다." />;
}
