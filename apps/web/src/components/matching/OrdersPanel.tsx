"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Bar, Btn, DataTable, Num, Panel, Pill, fmtNum, type Column } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { downloadCsv } from "@/components/portfolio/csv";
import { fmtDateTime } from "@/components/portfolio/format";
import { STATUS_META, type FillDto, type OrderDto } from "./data";

const FLOW = ["접수", "예약", "부분 체결", "체결", "정산"];
const FLOW_INDEX: Record<string, number> = { PENDING: 1, PARTIALLY_FILLED: 2, FILLED: 3 };

/** 접수 → 예약 → 부분 체결 → 체결 → 정산 중 주문이 어디 있는지 */
function StatusFlow({ order }: { order: OrderDto | null }) {
  const cur = order ? FLOW_INDEX[order.status] ?? -1 : -1;
  return (
    <div className="flex flex-wrap items-center gap-2 px-1.5 pb-2.5 pt-1.5">
      <ol className="m-0 flex list-none flex-wrap items-center gap-2 p-0" aria-label="주문 상태 흐름">
        {FLOW.map((s, i) => (
          <li key={s} className="flex items-center gap-2">
            <span
              aria-current={i === cur ? "step" : undefined}
              className={cn(
                "rounded-lg px-2.5 py-1.5 text-xs",
                i === cur ? "bg-dracula-purple font-bold text-tm-page" : i < cur ? "bg-[#22392c] text-dracula-green" : "border border-dashed border-tm-line2 text-tm-muted",
              )}
            >
              {s}
            </span>
            {i < FLOW.length - 1 && <span aria-hidden className="text-tm-muted">→</span>}
          </li>
        ))}
      </ol>
      <span className="ml-2 text-xs text-tm-muted">{order ? `ORD-${order.id} 현재 단계` : "진행 중인 주문이 없습니다"}</span>
    </div>
  );
}

/** 시안 Matching 하단 — 미체결 · 체결 내역 · 주문 상태 흐름 */
export function OrdersPanel({ orders, fills, stockName }: { orders: OrderDto[]; fills: FillDto[]; stockName: (id: number) => string }) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [tab, setTab] = useState("open");
  const [focusId, setFocusId] = useState<number | null>(null);
  const focus = orders.find((o) => o.id === focusId) ?? orders[0] ?? null;

  const cancelMutation = useMutation({
    mutationFn: async (orderId: number) => {
      const res = await authFetch(`/api/matching/orders/${orderId}`, { method: "DELETE" });
      // V-L8 — res.ok를 확인하지 않으면 취소 실패(이미 체결됐거나 권한 없음 등)가
      // 성공처럼 처리돼 목록이 갱신되고 사용자는 실패를 알 방법이 없다.
      if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.message ?? "주문 취소에 실패했습니다."); }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["matching", "orders"] }),
    onError: (e) => toast({ type: "error", title: "주문 취소 실패", message: (e as Error).message }),
  });

  const orderCols: Column<OrderDto>[] = [
    { key: "id", header: "주문번호", cell: (o) => <Num className="text-tm-muted">ORD-{o.id}</Num> },
    { key: "name", header: "종목", cell: (o) => stockName(o.stockId) },
    { key: "side", header: "구분", cell: (o) => <span className={o.side === "BUY" ? "text-up" : "text-down"}>{o.side === "BUY" ? "매수" : "매도"}</span> },
    { key: "price", header: "가격", align: "right", cell: (o) => (o.limitPrice != null ? <Num>{fmtNum(o.limitPrice)}</Num> : <span className="text-tm-muted">시장가</span>) },
    { key: "qty", header: "수량", align: "right", cell: (o) => <Num>{fmtNum(o.quantity)}</Num> },
    {
      key: "progress",
      header: "체결 진행",
      cell: (o) => (
        <div className="flex min-w-[140px] items-center gap-2">
          <div className="flex-1"><Bar pct={o.quantity > 0 ? (o.filledQty / o.quantity) * 100 : 0} /></div>
          <span className="num text-xs">{fmtNum(o.filledQty)} / {fmtNum(o.quantity)}</span>
        </div>
      ),
    },
    { key: "status", header: "상태", cell: (o) => { const m = STATUS_META[o.status] ?? { label: o.status, tone: "muted" as const }; return <span title={o.rejectReason ?? undefined}><Pill tone={m.tone}>{m.label}</Pill></span>; } },
    {
      key: "act",
      header: <span className="sr-only">취소</span>,
      align: "right",
      cell: (o) =>
        (o.status === "PENDING" || o.status === "PARTIALLY_FILLED") && (
          <Btn kind="danger" size="sm" aria-label={`ORD-${o.id} 취소`} disabled={cancelMutation.isPending} onClick={(e) => { e.stopPropagation(); cancelMutation.mutate(o.id); }}>
            취소
          </Btn>
        ),
    },
  ];

  const fillCols: Column<FillDto>[] = [
    { key: "t", header: "시각", cell: (f) => <Num className="text-tm-muted">{fmtDateTime(f.filledAt)}</Num> },
    { key: "o", header: "주문번호", cell: (f) => <Num className="text-tm-muted">ORD-{f.orderId}</Num> },
    { key: "n", header: "종목", cell: (f) => stockName(f.stockId) },
    { key: "s", header: "구분", cell: (f) => <span className={f.side === "BUY" ? "text-up" : "text-down"}>{f.side === "BUY" ? "매수" : "매도"}</span> },
    { key: "q", header: "수량", align: "right", cell: (f) => <Num>{fmtNum(f.quantity)}</Num> },
    { key: "p", header: "체결가", align: "right", cell: (f) => <Num>{fmtNum(f.fillPrice)}</Num> },
    { key: "a", header: "금액", align: "right", cell: (f) => <Num>{fmtNum(f.amount)}</Num> },
    { key: "f", header: "수수료", align: "right", cell: (f) => <Num>{fmtNum(f.fee)}</Num> },
  ];

  const exportCsv = () =>
    tab === "fills"
      ? downloadCsv("matching-fills.csv", ["시각", "주문번호", "종목", "구분", "수량", "체결가", "금액", "수수료"], fills.map((f) => [f.filledAt, f.orderId, stockName(f.stockId), f.side, f.quantity, f.fillPrice, f.amount, f.fee]))
      : downloadCsv("matching-open-orders.csv", ["주문번호", "종목", "구분", "유형", "가격", "수량", "체결", "상태"], orders.map((o) => [o.id, stockName(o.stockId), o.side, o.orderType, o.limitPrice ?? "", o.quantity, o.filledQty, o.status]));

  return (
    <Panel
      tabs={[{ key: "open", label: `미체결 ${orders.length}` }, { key: "fills", label: "체결 내역" }, { key: "flow", label: "주문 상태 흐름" }]}
      active={tab}
      onTabChange={setTab}
      actions={["download", "expand"]}
      onAction={(a) => a === "download" && exportCsv()}
      bodyClassName="px-1.5 pb-1.5 pt-2"
    >
      {tab !== "fills" && <StatusFlow order={focus} />}
      {tab === "fills" ? (
        <DataTable columns={fillCols} rows={fills.slice(0, 30)} rowKey={(f) => f.id} minWidth={760} empty="체결 내역 없음" />
      ) : (
        <DataTable
          columns={orderCols}
          rows={orders}
          rowKey={(o) => o.id}
          minWidth={760}
          selectedIndex={focus ? orders.indexOf(focus) : undefined}
          onRowClick={(o) => setFocusId(o.id)}
          empty="미체결 주문 없음"
        />
      )}
    </Panel>
  );
}
