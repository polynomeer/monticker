"use client";

import { useState } from "react";
import type { PortfolioTransactionType } from "@monticker/types";
import { Btn, Field, Notice, SelectBox, TextField } from "@/components/terminal";
import { SelectedStock, StockSearchBox, type StockHit } from "@/components/brokerage/shared";
import { useAddTransaction } from "@/hooks/usePortfolio";
import { TX_TYPE_LABEL } from "./labels";

const NEEDS_INSTRUMENT: PortfolioTransactionType[] = ["BUY", "SELL", "DIVIDEND"];
const isTrade = (t: PortfolioTransactionType) => t === "BUY" || t === "SELL";

/** 숫자 입력 — 천 단위 쉼표를 지우고 읽는다. 비었으면 null, 숫자가 아니면 NaN */
function num(s: string): number | null {
  const t = s.replace(/,/g, "").trim();
  return t === "" ? null : Number(t);
}

/** 오늘(KST) — 서버도 미래 날짜를 거부한다 */
function todayKst() {
  return new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10);
}

/** 손 입력 계좌에 거래 한 건 — 검증 규칙은 서버(TransactionNormalizer)와 같고, 서버가 최종 판단한다 */
export function AddTransactionForm({ accountId }: { accountId: number }) {
  const [type, setType] = useState<PortfolioTransactionType>("BUY");
  const [date, setDate] = useState(todayKst());
  const [stock, setStock] = useState<StockHit | null>(null);
  const [unlisted, setUnlisted] = useState(false);
  const [code, setCode] = useState("");
  const [instName, setInstName] = useState("");
  const [qty, setQty] = useState("");
  const [price, setPrice] = useState("");
  const [amount, setAmount] = useState("");
  const [fee, setFee] = useState("");
  const [tax, setTax] = useState("");
  const [currency, setCurrency] = useState("");
  const [memo, setMemo] = useState("");
  const add = useAddTransaction(accountId);

  const trade = isTrade(type);
  const needsInstrument = NEEDS_INSTRUMENT.includes(type);
  const values = { qty: num(qty), price: num(price), amount: num(amount), fee: num(fee), tax: num(tax) };
  const bad = Object.values(values).some((v) => v != null && (Number.isNaN(v) || v < 0));
  const hasInstrument = !needsInstrument || (unlisted ? code.trim() !== "" : stock != null);
  const valid = !bad && date !== "" && date <= todayKst() && hasInstrument
    && (trade ? (values.qty ?? 0) > 0 && values.price != null : (values.amount ?? 0) > 0);

  const reset = () => { setQty(""); setPrice(""); setAmount(""); setFee(""); setTax(""); setMemo(""); };

  const submit = async () => {
    try {
      await add.mutateAsync({
        type, tradeDate: date,
        stockId: needsInstrument && !unlisted ? stock?.id ?? null : null,
        instrumentCode: needsInstrument && unlisted ? code.trim() : null,
        instrumentName: needsInstrument && unlisted ? instName.trim() || null : null,
        quantity: trade ? values.qty : null,
        price: trade ? values.price : null,
        amount: trade ? null : values.amount,
        fee: values.fee, tax: values.tax,
        // 종목이 있으면 서버가 종목 통화를 쓴다
        currency: needsInstrument && !unlisted ? null : currency.trim() || null,
        memo: memo.trim() || null,
      });
      reset();
    } catch {
      // 아래 Notice
    }
  };

  return (
    <form className="flex flex-col gap-2.5" onSubmit={(e) => { e.preventDefault(); if (valid) submit(); }}>
      <div className="flex gap-2">
        <SelectBox label="구분" value={type} onChange={(e) => setType(e.target.value as PortfolioTransactionType)}>
          {Object.entries(TX_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </SelectBox>
        <Field label="거래일" type="date" value={date} max={todayKst()} onChange={(e) => setDate(e.target.value)} />
      </div>

      {needsInstrument && (
        <div className="flex flex-col gap-1.5">
          {unlisted ? (
            <div className="flex gap-2">
              <Field label="상품 코드" value={code} maxLength={30} onChange={(e) => setCode(e.target.value)} />
              <Field label="상품 이름" mono={false} value={instName} maxLength={200} onChange={(e) => setInstName(e.target.value)} />
            </div>
          ) : stock ? (
            <SelectedStock stock={stock} onClear={() => setStock(null)} />
          ) : (
            <StockSearchBox onSelect={setStock} />
          )}
          <button type="button" className="self-start text-xs text-tm-muted hover:text-dracula-fg" onClick={() => { setUnlisted(!unlisted); setStock(null); }}>
            {unlisted ? "목록에서 종목 찾기" : "목록에 없는 상품(펀드·채권 등) 직접 적기 — 시세는 나오지 않습니다"}
          </button>
        </div>
      )}

      {trade ? (
        <div className="flex gap-2">
          <Field label="수량" inputMode="decimal" value={qty} onChange={(e) => setQty(e.target.value)} />
          <Field label="단가" inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
        </div>
      ) : (
        <Field label="금액" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
      )}
      <div className="flex gap-2">
        <Field label="수수료" inputMode="decimal" value={fee} onChange={(e) => setFee(e.target.value)} />
        <Field label="세금" inputMode="decimal" value={tax} onChange={(e) => setTax(e.target.value)} />
        {(!needsInstrument || unlisted) && (
          <Field label="통화" placeholder="KRW" value={currency} maxLength={3} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
        )}
      </div>
      <TextField label="메모 (선택)" value={memo} maxLength={200} onChange={(e) => setMemo(e.target.value)} />
      {bad && <Notice tone="warn">숫자는 0 이상으로 적습니다.</Notice>}
      {add.isError && <Notice tone="danger">{add.error instanceof Error ? add.error.message : "거래를 넣지 못했습니다."}</Notice>}
      <Btn type="submit" disabled={!valid || add.isPending} className="self-end">{add.isPending ? "넣는 중…" : "거래 추가"}</Btn>
    </form>
  );
}
