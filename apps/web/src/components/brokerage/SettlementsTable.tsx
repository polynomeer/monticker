"use client";

import type { BrokerageSettlementResponse } from "@monticker/types";
import { DataTable, Pill, fmtNum, type Column } from "@/components/terminal";
import { sideClass, sideLabel } from "./shared";

export const SETTLEMENT_STATUS: Record<string, { label: string; tone: "orange" | "green" | "red" }> = {
  PENDING: { label: "대기 중",   tone: "orange" },
  SETTLED: { label: "정산 완료", tone: "green" },
  FAILED:  { label: "실패",      tone: "red" },
};

const COLS: Column<BrokerageSettlementResponse>[] = [
  { key: "date", header: "정산 예정일", cell: s => <span className="num text-tm-muted">{new Date(s.settleDate).toLocaleDateString("ko-KR")}</span> },
  { key: "sym", header: "종목", cell: s => <span className="font-medium">{s.symbol}</span> },
  { key: "side", header: "구분", cell: s => <span className={sideClass(s.side)}>{sideLabel(s.side)}</span> },
  { key: "qty", header: "체결 수량", align: "right", cell: s => <span className="num">{fmtNum(s.quantity)}</span> },
  { key: "price", header: "체결가", align: "right", cell: s => <span className="num">{fmtNum(s.fillPrice)}</span> },
  { key: "fee", header: "수수료·세금", align: "right", cell: s => <span className="num text-tm-muted">{fmtNum(s.fee + s.tax)}</span> },
  { key: "net", header: "정산 금액", align: "right", cell: s => <span className={`num ${sideClass(s.side)}`}>{s.side === "BUY" ? "-" : "+"}{fmtNum(s.netAmount)}</span> },
  { key: "status", header: "상태", cell: s => { const m = SETTLEMENT_STATUS[s.status]; return <Pill tone={m?.tone ?? "muted"}>{m?.label ?? s.status}</Pill>; } },
];

/** 실계좌 정산(체결) 내역 표. */
export function SettlementsTable({ rows }: { rows: BrokerageSettlementResponse[] }) {
  return <DataTable columns={COLS} rows={rows} rowKey={s => s.id} minWidth={760} empty="정산 내역이 없습니다." />;
}
