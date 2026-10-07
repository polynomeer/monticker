"use client";

import type { TradeHistory } from "@/hooks/usePaperTrade";
import { Chip, DataTable, Num, fmtNum, type Column } from "@/components/terminal";
import { emotionLabel } from "@/components/wallet/emotions";
import { OriginBadge } from "@/components/wallet/origin";
import { fmtDateTime } from "./format";

/** 거래 경로 — 서버가 주문 제출 경로로 정해 체결에 남긴 진입 출처(ADR-085). */
function RouteCell({ h }: { h: TradeHistory }) {
  const limit = h.orderType === "LIMIT" && (h.source === "MANUAL" || h.source === "CONDITIONAL") ? " · 지정가" : "";
  return <OriginBadge origin={h.source} originRef={h.originRef ?? h.watchRuleId ?? h.conditionalOrderId} suffix={limit} />;
}

/** 시안 Portfolio "거래 내역" 표. 감정 태그는 내역 응답에 함께 온다(예전엔 거래마다 따로 조회했다). */
export function TradeHistoryTable({ history, limit = 20 }: { history: TradeHistory[]; limit?: number }) {
  const rows = history.slice(0, limit);
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
        const l = emotionLabel(h.emotion);
        return l ? <span title={h.emotionMemo ?? undefined}><Chip className="h-[22px]">{l}</Chip></span> : <span className="text-tm-muted">—</span>;
      },
    },
    { key: "route", header: "경로", cell: (h) => <RouteCell h={h} /> },
  ];
  return <DataTable columns={cols} rows={rows} rowKey={(h) => h.id} minWidth={800} empty="거래 내역이 없습니다." />;
}
