"use client";

import { useState } from "react";
import { Btn, BuySell, Field, Pill, SelectBox, fmtNum } from "@/components/terminal";
import {
  sellableQuantity,
  usePaperConditionalMutations,
  usePaperConditionalOrders,
  usePaperOpenOrders,
  usePaperPortfolio,
  type PaperConditionalOrder,
  type PaperTriggerType,
} from "@/hooks/usePaperTrade";
import { useToast } from "@/hooks/useToast";

export const PAPER_TRIGGER_LABEL: Record<PaperTriggerType, string> = {
  TAKE_PROFIT: "익절", STOP_LOSS: "손절", PRICE_ABOVE: "가격 이상", PRICE_BELOW: "가격 이하",
};
export const PAPER_COND_STATUS: Record<PaperConditionalOrder["status"], { label: string; tone: "green" | "muted" | "orange" | "red" | "cyan" | "yellow" }> = {
  WAITING_PARENT: { label: "체결 대기", tone: "yellow" },
  ACTIVE: { label: "감시 중", tone: "cyan" },
  EXECUTED: { label: "체결", tone: "green" },
  CANCELLED: { label: "취소", tone: "muted" },
  FAILED: { label: "실패", tone: "red" },
};

type Mode = "OCO" | "TAKE_PROFIT" | "STOP_LOSS" | "PRICE_ABOVE" | "PRICE_BELOW";

/**
 * 주문 패널 "조건부" 탭 — 모의투자 조건부 주문(ADR-075). 조건이 맞으면 모의 매칭 엔진에 시장가 주문이 나간다.
 * 실전 계좌로는 절대 가지 않는다 — 실전 조건부 주문은 실전투자 화면에서만.
 */
export default function PaperConditionalPanel({ stockId, currentPrice }: { stockId: number; currentPrice: number }) {
  const [side, setSide] = useState<"BUY" | "SELL">("SELL");
  const [mode, setMode] = useState<Mode>("OCO");
  const [p1, setP1] = useState("");
  const [p2, setP2] = useState("");
  const [qty, setQty] = useState(1);
  const { toast } = useToast();
  const { data: portfolio } = usePaperPortfolio();
  const { data: openOrders = [] } = usePaperOpenOrders();
  const { data: rows = [], isLoading } = usePaperConditionalOrders(stockId);
  const { create, cancel } = usePaperConditionalMutations();

  const owned = portfolio?.holdings.find((h) => h.stockId === stockId)?.quantity ?? 0;
  const sellable = sellableQuantity(owned, openOrders, stockId);
  const isSell = side === "SELL";
  const modes: Mode[] = isSell ? ["OCO", "TAKE_PROFIT", "STOP_LOSS", "PRICE_ABOVE", "PRICE_BELOW"] : ["PRICE_BELOW", "PRICE_ABOVE"];
  const effectiveMode = modes.includes(mode) ? mode : modes[0];
  const n1 = Number(p1.replace(/,/g, ""));
  const n2 = Number(p2.replace(/,/g, ""));

  const legs: { triggerType: PaperTriggerType; triggerPrice: number }[] =
    effectiveMode === "OCO" ? [{ triggerType: "TAKE_PROFIT", triggerPrice: n1 }, { triggerType: "STOP_LOSS", triggerPrice: n2 }] : [{ triggerType: effectiveMode, triggerPrice: n1 }];
  const error =
    !Number.isInteger(qty) || qty < 1 ? "수량은 1 이상" :
    isSell && qty > owned ? `보유 수량 초과 (보유 ${fmtNum(owned)}주)` :
    legs.some((l) => !(l.triggerPrice > 0)) ? null :
    effectiveMode === "OCO" && !(n1 > n2) ? "익절가는 손절가보다 높아야 합니다" : null;
  const ready = legs.every((l) => l.triggerPrice > 0) && !error;

  const submit = () => {
    create.mutate({ stockId, side, quantity: qty, legs }, {
      onSuccess: () => {
        toast({ type: "success", title: "조건부 주문 등록", message: "조건이 맞으면 모의투자 시장가로 체결됩니다." });
        setP1(""); setP2("");
      },
      onError: (e) => toast({ type: "error", title: "등록 실패", message: (e as Error).message }),
    });
  };

  const live = rows.filter((r) => r.status === "ACTIVE" || r.status === "WAITING_PARENT");
  const done = rows.filter((r) => !(r.status === "ACTIVE" || r.status === "WAITING_PARENT")).slice(0, 5);

  return (
    <div className="flex flex-col gap-3">
      <BuySell value={side} onChange={setSide} />
      <SelectBox label="조건" value={effectiveMode} onChange={(e) => setMode(e.target.value as Mode)}>
        {modes.map((m) => (
          <option key={m} value={m}>{m === "OCO" ? "익절 + 손절 (OCO)" : PAPER_TRIGGER_LABEL[m]}</option>
        ))}
      </SelectBox>
      <div className="flex gap-2">
        <Field
          label={effectiveMode === "OCO" ? "익절가 (이상)" : effectiveMode === "PRICE_BELOW" || effectiveMode === "STOP_LOSS" ? "발동가 (이하)" : "발동가 (이상)"}
          unit="원" type="number" inputMode="decimal" min={0} step="any" placeholder={currentPrice > 0 ? fmtNum(currentPrice) : "—"}
          value={p1} onChange={(e) => setP1(e.target.value)}
        />
        {effectiveMode === "OCO" && (
          <Field label="손절가 (이하)" unit="원" type="number" inputMode="decimal" min={0} step="any" value={p2} onChange={(e) => setP2(e.target.value)} />
        )}
      </div>
      <Field
        label={isSell ? `수량 (보유 ${fmtNum(owned)}주 · 매도 가능 ${fmtNum(sellable)}주)` : "수량"}
        unit="주" type="number" inputMode="numeric" min={1} step={1} value={qty}
        onChange={(e) => setQty(Math.max(1, Math.floor(Number(e.target.value)) || 1))}
      />
      {error && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{error}</p>}
      <p className="m-0 text-2xs leading-normal text-tm-muted">
        현재가 {currentPrice > 0 ? `${fmtNum(currentPrice)}원` : "—"} · 조건이 맞으면 모의투자 시장가로 주문합니다(리스크 한도 적용). 그 사이 보유를 팔면 발동 시 실패로 남습니다.
      </p>
      <Btn kind={isSell ? "sell" : "buy"} full onClick={submit} disabled={!ready || create.isPending}>
        {create.isPending ? "등록 중..." : "조건부 주문 등록"}
      </Btn>

      <div className="flex flex-col gap-1.5">
        <span className="text-2xs text-tm-muted">이 종목 조건부 주문</span>
        {isLoading ? (
          <div className="h-12 animate-pulse rounded-lg bg-tm-inner" />
        ) : live.length + done.length === 0 ? (
          <p className="m-0 text-xs text-tm-muted">등록된 조건부 주문이 없습니다.</p>
        ) : (
          [...live, ...done].map((r) => {
            const st = PAPER_COND_STATUS[r.status];
            return (
              <div key={r.id} className="flex items-center justify-between gap-2 rounded-md bg-tm-inner px-2.5 py-1.5 text-xs" title={r.failReason ?? undefined}>
                <span className="flex items-center gap-1.5">
                  <span className={r.side === "BUY" ? "text-up" : "text-down"}>{r.side === "BUY" ? "매수" : "매도"}</span>
                  {PAPER_TRIGGER_LABEL[r.triggerType]} <span className="num">{fmtNum(r.triggerPrice)}</span>
                  <span className="num text-tm-muted">× {fmtNum(r.quantity)}</span>
                  {r.ocoGroupId && <Pill tone="purple">OCO</Pill>}
                </span>
                <span className="flex items-center gap-2">
                  <Pill tone={st.tone}>{st.label}</Pill>
                  {(r.status === "ACTIVE" || r.status === "WAITING_PARENT") && (
                    <button
                      type="button"
                      disabled={cancel.isPending}
                      onClick={() => cancel.mutate(r.id, { onError: (e) => toast({ type: "error", title: "취소 실패", message: (e as Error).message }) })}
                      className="text-[#ff8a8a] hover:underline disabled:opacity-40"
                    >
                      취소
                    </button>
                  )}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
