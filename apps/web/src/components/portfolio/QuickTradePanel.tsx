"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { usePaperTrade, type Portfolio } from "@/hooks/usePaperTrade";
import { authFetch } from "@/services/api";
import { useRiskPreview } from "@/hooks/useRiskPreview";
import { RULE_LABELS } from "@/components/risk/useRiskDecisions";
import TradeReceipt from "@/components/wallet/TradeReceipt";
import { Btn, BuySell, Field, Icon, KV, fmtNum } from "@/components/terminal";
import type { StockMeta } from "./useStockMeta";

interface StockHit { id: number; symbol: string; name: string; }
interface Picked extends StockHit { price: number; }

// 매수·매도 모두 주문 수량 상한 — 실수로 1e9 같은 값을 넣는 걸 화면에서 먼저 막는다
const MAX_QTY = 1_000_000;

/**
 * 시안 Portfolio "빠른 매수" — 종목 검색 → 방향·수량 → 모의 주문.
 * 체결되면 TradeModal과 같은 영수증(감정 태그 포함)을 띄운다.
 */
export function QuickTradePanel({ portfolio, meta, sectorValue }: {
  portfolio: Portfolio | undefined;
  meta: Map<number, StockMeta>;
  /** 섹터 이름 → 현재 평가액 (주문 후 섹터 비중 계산용) */
  sectorValue: Map<string, number>;
}) {
  const inputId = useId();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<StockHit[]>([]);
  const [stock, setStock] = useState<Picked | null>(null);
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [qty, setQty] = useState("1");
  const [error, setError] = useState("");
  const [receiptTradeId, setReceiptTradeId] = useState<number | null>(null);
  const { buy, sell } = usePaperTrade();

  useEffect(() => {
    if (query.length < 1) { setResults([]); return; }
    const controller = new AbortController();
    const t = setTimeout(async () => {
      try {
        const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}`, { signal: controller.signal });
        if (r.ok) setResults((await r.json()).slice(0, 6));
      } catch { /* 취소·네트워크 오류는 결과 없음으로 */ }
    }, 200);
    return () => { clearTimeout(t); controller.abort(); };
  }, [query]);

  const pick = async (s: StockHit) => {
    setQuery(""); setResults([]); setError("");
    const r = await fetch(`/api/stocks/${s.id}/price`).catch(() => null);
    const data = r && r.ok ? await r.json() : null;
    setStock({ ...s, price: Number(data?.price ?? 0) });
  };

  const { data: receipt } = useQuery({
    queryKey: ["receipt", receiptTradeId],
    queryFn: async () => {
      const res = await authFetch(`/api/paper/trades/${receiptTradeId}/receipt`);
      return res.ok ? res.json() : null;
    },
    enabled: receiptTradeId != null,
  });

  const price = stock?.price ?? 0;
  const n = Number(qty);
  const held = stock ? portfolio?.holdings.find((h) => h.stockId === stock.id)?.quantity ?? 0 : 0;
  const cash = portfolio?.cash ?? 0;
  const max = side === "BUY" ? (price > 0 ? Math.floor(cash / price) : 0) : held;
  const amount = Number.isFinite(n) ? n * price : 0;
  const qtyValid = Number.isInteger(n) && n > 0 && n <= MAX_QTY;
  const pending = buy.isPending || sell.isPending;

  // 주문 전 리스크 — ADR-092 미리보기(감사 기록 없음, 400ms 디바운스). 이 패널은 모의 주문이라 미리보기가 판정하는 모의계좌와
  // 같은 계좌다. 한도에 걸리면 제출을 막고 이유를 보여 준다 — 제출 시 서버 게이트가 다시 판정하는 것은 그대로다.
  const risk = useRiskPreview(
    stock ? { stockId: stock.id, side, quantity: n, estimatedPrice: price } : null,
    !!stock && qtyValid && price > 0,
  );
  const failed = risk.data && !risk.data.approved ? risk.data.checks.find((c) => !c.passed) ?? null : null;
  const riskBlocked = !!risk.data && !risk.data.approved;
  const riskReason = riskBlocked
    ? `${failed ? `${RULE_LABELS[failed.rule] ?? failed.rule} 한도에 걸립니다` : "리스크 한도에 걸립니다"}${failed?.detail ? ` · ${failed.detail}` : ""}`
    : null;

  // 주문 후 섹터 비중(참고) — 섹터를 아는 종목만. 차단 여부는 위 리스크 미리보기(섹터 한도를 설정했으면 섹터 규칙 포함)가 정한다.
  const sector = stock ? meta.get(stock.id)?.sector ?? null : null;
  const total = portfolio?.totalValue ?? 0;
  const afterPct = sector && total > 0 && qtyValid
    ? (((sectorValue.get(sector) ?? 0) + (side === "BUY" ? amount : -Math.min(amount, held * price))) / total) * 100
    : null;

  const submit = async () => {
    setError("");
    if (!stock) { setError("종목을 선택해주세요."); return; }
    if (!qtyValid) { setError("수량은 1 이상의 정수여야 합니다."); return; }
    if (riskBlocked) return;
    if (n > max) { setError(side === "BUY" ? `잔고 부족 (최대 ${fmtNum(max)}주 가능)` : `보유 수량 초과 (최대 ${fmtNum(max)}주)`); return; }
    try {
      const m = side === "BUY" ? buy : sell;
      const result: { tradeId?: number; id?: number } = await m.mutateAsync({ stockId: stock.id, quantity: n });
      const tid = result?.tradeId ?? result?.id;
      if (tid) setReceiptTradeId(tid);
      setQty("1");
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      {stock ? (
        <div className="flex min-h-10 items-center justify-between gap-2.5 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5">
          <span className="flex flex-col gap-0.5">
            <span className="text-2xs text-tm-muted">종목</span>
            <span className="text-sm">{stock.name} · <span className="num">{price > 0 ? fmtNum(price) : "—"}</span></span>
          </span>
          <button type="button" onClick={() => setStock(null)} className="text-xs text-tm-muted hover:text-dracula-fg">변경</button>
        </div>
      ) : (
        <div className="relative">
          <label htmlFor={inputId} className="flex min-h-10 items-center gap-2.5 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5">
            <span className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="text-2xs text-tm-muted">종목</span>
              <input
                id={inputId}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                aria-label="종목 매수 검색"
                placeholder="종목명 또는 티커 검색"
                className="w-full bg-transparent p-0 text-sm text-dracula-fg outline-hidden placeholder:text-[#8b92b8]"
              />
            </span>
            <Icon name="search" size={16} className="text-tm-muted" />
          </label>
          {results.length > 0 && (
            <ul className="absolute left-0 right-0 top-full z-20 m-0 mt-1 list-none overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel p-0 shadow-glow-line">
              {results.map((s) => (
                <li key={s.id}>
                  <button type="button" onClick={() => pick(s)} className="flex w-full items-center justify-between px-3 py-2.5 text-left text-13 hover:bg-tm-raised">
                    <span className="flex flex-col">
                      <span className="font-semibold">{s.name}</span>
                      <span className="num text-2xs text-tm-muted">{s.symbol}</span>
                    </span>
                    <span className="text-xs text-dracula-purple">선택</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <BuySell value={side} onChange={setSide} />
      <Field label="수량" unit="주" type="number" inputMode="numeric" min={1} max={MAX_QTY} value={qty} onChange={(e) => setQty(e.target.value)} className="flex-none" />
      <KV k="예상 금액" v={stock && qtyValid ? `${fmtNum(amount)}원` : "—"} />
      <KV k={`주문 후 ${sector ?? "섹터"} 비중 (참고)`} v={afterPct != null ? `${afterPct.toFixed(1)}%` : "—"} valueClassName={afterPct != null && afterPct >= 40 ? "text-caution" : undefined} />
      {stock && <span className="num text-2xs text-tm-muted">{side === "BUY" ? `가용 현금 ${fmtNum(cash)}원 · 최대 ${fmtNum(max)}주` : `보유 ${fmtNum(held)}주`}</span>}
      {risk.pending && !risk.data && <span aria-live="polite" className="text-2xs text-tm-muted">리스크 한도 점검 중...</span>}
      {risk.error && <span className="text-2xs text-tm-muted">{risk.error.message} 주문할 때 서버가 다시 판정합니다.</span>}
      {riskReason && (
        <p role="alert" className="m-0 flex flex-wrap items-center gap-x-2 text-xs text-[#ff8a8a]">
          <span>{riskReason}</span>
          <Link href="/risk" className="text-2xs">리스크 한도 보기</Link>
        </p>
      )}
      {error && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{error}</p>}
      <Btn
        kind={side === "BUY" ? "buy" : "sell"}
        size="lg"
        full
        onClick={submit}
        disabled={pending || !stock || price <= 0 || riskBlocked}
        aria-disabled={pending || !stock || price <= 0 || riskBlocked}
      >
        {pending ? "처리 중..." : side === "BUY" ? "모의 매수" : "모의 매도"}
      </Btn>
      {receiptTradeId && receipt && <TradeReceipt receipt={receipt} onClose={() => setReceiptTradeId(null)} />}
    </>
  );
}
