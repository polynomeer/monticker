"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { BtnLink, BuySell, Btn, Chip, Field, Icon, PreviewTag, SelectBox, fmtNum } from "@/components/terminal";
import TradeReceipt from "@/components/wallet/TradeReceipt";
import { sellableQuantity, usePaperOpenOrders, usePaperOrder, usePaperPortfolio } from "@/hooks/usePaperTrade";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";
import { authFetch } from "@/services/api";
import { cn } from "@/lib/utils";

interface Props {
  stock: { id: number; symbol: string; name: string };
  currentPrice: number;
  /** 실전 계좌가 연동돼 있으면 실전투자 화면 안내를 보여 준다(이 폼은 실주문을 보내지 않는다) */
  brokerageConnected?: boolean;
}

interface RuleResult { rule: string; passed: boolean; detail: string; current: number; limit: number; }
interface RiskCheckResult { approved: boolean; blockedBy: string | null; severity: string; checks: RuleResult[]; }

const RULE_LABEL: Record<string, string> = {
  VaRRule: "1일 VaR 95%",
  ConcentrationRule: "종목 집중도",
  DailyLossRule: "일간 손실 한도",
  PositionCountRule: "보유 종목 수",
  TradingFrequencyRule: "시간당 주문 수",
  QuantityRule: "주문 수량",
};

function ruleValue(r: RuleResult) {
  switch (r.rule) {
    case "VaRRule":
    case "ConcentrationRule":
      return `${r.current.toFixed(1)}% / ${r.limit}%`;
    case "PositionCountRule":
      return `${fmtNum(r.current)}개 / ${fmtNum(r.limit)}개`;
    case "TradingFrequencyRule":
      return `${fmtNum(r.current)}건 / ${fmtNum(r.limit)}건`;
    case "DailyLossRule":
      return `${fmtNum(r.current)} / ${fmtNum(r.limit)}원`;
    default:
      return r.passed ? "통과" : "차단";
  }
}

/** 시안의 감정 태그 4개 → 백엔드 EmotionType. "계획대로"는 대응 값이 없어 OTHER + 메모로 남긴다. */
const EMOTION_TAGS = [
  { key: "확신", emotion: "CONFIDENT", memo: null },
  { key: "계획대로", emotion: "OTHER", memo: "계획대로" },
  { key: "FOMO", emotion: "FOMO", memo: null },
  { key: "불안", emotion: "ANXIOUS", memo: null },
] as const;

/**
 * 시안 Main의 주문 패널 — 모의투자 주문(`POST /api/paper/orders`, ADR-074). 시장가는 즉시 체결,
 * 지정가는 교차하면 즉시 체결·아니면 미체결로 남아 서버 스위퍼가 시세 교차 시 체결한다.
 * 실전 탭은 이 폼에서 주문을 보내지 않는다 — 실주문은 검증 경로가 있는 실전투자 화면에서만.
 */
export default function OrderForm({ stock, currentPrice, brokerageConnected }: Props) {
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("MARKET");
  const [limitInput, setLimitInput] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState("");
  const [emotionKey, setEmotionKey] = useState<string | null>(null);
  const [receiptTradeId, setReceiptTradeId] = useState<number | null>(null);
  const { place } = usePaperOrder();
  const { data: portfolio } = usePaperPortfolio();
  const { isLoggedIn } = useAuth();
  const { data: openOrders = [] } = usePaperOpenOrders(isLoggedIn);
  const { toast } = useToast();

  const isBuy = side === "BUY";
  const isLimit = orderType === "LIMIT";
  const limitPrice = Number(limitInput.replace(/,/g, ""));
  const limitValid = !isLimit || (Number.isFinite(limitPrice) && limitPrice > 0);
  // 지정가 매수는 서버가 지정가 × 수량을 예약한다 — 주문 가능 수량도 지정가로 계산한다
  const unitPrice = isLimit ? (limitValid ? limitPrice : 0) : currentPrice;
  const totalAmount = quantity * unitPrice;
  const cash = portfolio?.cash ?? 0;
  const holding = portfolio?.holdings.find((h) => h.stockId === stock.id);
  const ownedQty = holding?.quantity ?? 0;
  const sellable = sellableQuantity(ownedQty, openOrders, stock.id);
  const maxBuy = unitPrice > 0 ? Math.floor(cash / unitPrice) : 0;
  const max = isBuy ? maxBuy : sellable;
  const isValid = Number.isInteger(quantity) && quantity > 0 && quantity <= max && limitValid;
  const isPending = place.isPending;
  // 지정가가 이미 교차하면 즉시 체결된다(서버 사가와 같은 판정)
  const crossesNow = isLimit && limitValid && currentPrice > 0 && (isBuy ? limitPrice >= currentPrice : limitPrice <= currentPrice);
  const ratio = max > 0 ? Math.min(100, Math.round((quantity / max) * 100)) : 0;

  const { data: receipt } = useQuery({
    queryKey: ["receipt", receiptTradeId],
    queryFn: async () => {
      const res = await authFetch(`/api/paper/trades/${receiptTradeId}/receipt`);
      if (!res.ok) return null;
      return res.json();
    },
    enabled: receiptTradeId != null,
  });

  // 주문 전 리스크 체크 — 서버 판정(POST /api/risk/check)은 감사 로그를 남기므로 입력마다가 아니라 버튼으로만 부른다.
  const risk = useMutation({
    mutationFn: async (): Promise<RiskCheckResult> => {
      const res = await authFetch("/api/risk/check", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId: stock.id, side, quantity, estimatedPrice: unitPrice }),
      });
      if (!res.ok) {
        const e = await res.json().catch(() => null);
        throw new Error(e?.message ?? "리스크 점검 실패");
      }
      return res.json();
    },
  });
  const resetRisk = risk.reset;
  // 수량·방향·가격이 바뀌면 이전 점검 결과는 더 이상 이 주문의 결과가 아니다.
  useEffect(() => { resetRisk(); }, [side, quantity, orderType, limitInput, resetRisk]);

  const setQty = (raw: number) => setQuantity(Math.min(Math.max(max, 1), Math.max(1, Math.floor(raw) || 1)));

  const handleSubmit = async () => {
    setError("");
    try {
      const result = await place.mutateAsync({
        stockId: stock.id, side, orderType, quantity, ...(isLimit ? { limitPrice } : {}),
      });
      if (result.status === "PENDING") {
        toast({
          type: "success",
          title: "지정가 주문 접수",
          message: `${fmtNum(limitPrice)}원 ${fmtNum(quantity)}주 ${isBuy ? "매수" : "매도"} — 가격이 닿으면 체결됩니다. 아래 '미체결 주문'에서 취소할 수 있어요.`,
        });
        setQuantity(1);
        return;
      }
      const tid = result.tradeId;
      if (tid) {
        const tag = EMOTION_TAGS.find((t) => t.key === emotionKey);
        if (tag) {
          // 영수증 카드가 저장된 감정을 읽어 미리 선택하도록 먼저 저장한다(서버는 같은 거래의 태그를 덮어쓴다).
          const r = await authFetch(`/api/paper/trades/${tid}/emotion`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ emotion: tag.emotion, memo: tag.memo }),
          }).catch(() => null);
          if (!r?.ok) toast({ type: "error", title: "감정 태그 저장 실패", message: "영수증에서 다시 선택할 수 있습니다." });
        }
        setReceiptTradeId(tid);
        setQuantity(1);
        setEmotionKey(null);
      }
    } catch (e: unknown) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      {receiptTradeId && receipt && <TradeReceipt receipt={receipt} onClose={() => setReceiptTradeId(null)} />}

      {/* 계좌 선택 — 실전은 이 폼에서 주문하지 않는다 */}
      <div className="flex gap-0.5 rounded-lg bg-tm-inner p-[3px]">
        <button type="button" aria-pressed className="h-[30px] flex-1 rounded-md bg-tm-line2 px-3 text-13 font-semibold">모의투자</button>
        <button
          type="button"
          aria-pressed={false}
          disabled
          title="실전 주문은 실전투자 화면에서 합니다"
          className="h-[30px] flex-1 cursor-not-allowed rounded-md px-3 text-13 text-tm-muted"
        >
          {brokerageConnected ? "실전 · 실전투자 화면" : "실전 · 연동 필요"}
        </button>
      </div>
      {brokerageConnected && (
        <Link href="/brokerage" className="-mt-1.5 text-2xs">실전 주문은 실전투자 화면에서 할 수 있어요 →</Link>
      )}

      {!isLoggedIn ? (
        <div className="flex flex-col items-center gap-2 py-8 text-center">
          <p className="m-0 text-sm font-semibold">로그인하고 모의투자를 시작하세요</p>
          <p className="m-0 text-xs text-tm-muted">가상 자금으로 매매 연습을 해볼 수 있어요</p>
          <BtnLink href="/login" kind="primary" size="sm" className="mt-1">로그인</BtnLink>
        </div>
      ) : (
        <>
          <BuySell value={side} onChange={setSide} />

          <SelectBox label="주문 유형" value={orderType} onChange={(e) => {
            const t = e.target.value as "MARKET" | "LIMIT";
            setOrderType(t);
            // 지정가로 바꿀 때 현재가로 채워 둔다 — 빈 칸에서 시작하면 엉뚱한 가격을 넣기 쉽다
            if (t === "LIMIT" && !limitInput && currentPrice > 0) setLimitInput(String(currentPrice));
          }}>
            <option value="MARKET">시장가</option>
            <option value="LIMIT">지정가</option>
          </SelectBox>

          {isLimit ? (
            <Field
              label={crossesNow ? "지정가 · 지금 가격이면 즉시 체결" : "지정가"}
              unit="원"
              type="number"
              inputMode="decimal"
              min={0}
              step="any"
              value={limitInput}
              onChange={(e) => setLimitInput(e.target.value)}
            />
          ) : (
            <Field label="시장가 · 현재가 기준" unit="원" value={currentPrice > 0 ? fmtNum(currentPrice) : "—"} readOnly tabIndex={-1} />
          )}

          <div className="flex gap-2">
            <Field
              label={`수량 (최대 ${fmtNum(max)}주)`}
              unit="주"
              type="number"
              inputMode="numeric"
              min={1}
              max={Math.max(max, 1)}
              step={1}
              value={quantity}
              onChange={(e) => setQty(Number(e.target.value))}
            />
            <Field label="총액" unit="원" value={`≈ ${fmtNum(totalAmount)}`} readOnly tabIndex={-1} />
          </div>

          {/* 비율 슬라이더 — 주문 가능 수량(매수: 현금/현재가, 매도: 보유 수량) 대비 */}
          <div className="flex flex-col gap-1.5">
            <div className="relative h-1 rounded-full bg-tm-inner">
              <div className={cn("h-full rounded-full", isBuy ? "bg-up" : "bg-down")} style={{ width: `${ratio}%` }} />
              <span aria-hidden className="absolute -top-[5px] -ml-[7px] h-3.5 w-3.5 rounded-full bg-dracula-fg" style={{ left: `${ratio}%` }} />
              <input
                type="range"
                aria-label="주문 가능 수량 대비 비율"
                min={0}
                max={100}
                step={1}
                value={ratio}
                disabled={max <= 0}
                onChange={(e) => setQty((max * Number(e.target.value)) / 100)}
                className="absolute -top-2 left-0 h-5 w-full cursor-pointer opacity-0 disabled:cursor-not-allowed"
              />
            </div>
            <div className="num flex justify-between text-2xs text-tm-muted">
              <span>{ratio}%</span>
              {[25, 50, 100].map((p) => (
                <button key={p} type="button" disabled={max <= 0} onClick={() => setQty((max * p) / 100)} className="hover:text-dracula-fg disabled:opacity-40">
                  {p}%
                </button>
              ))}
            </div>
            <div className="flex flex-wrap justify-between gap-x-3 text-2xs text-tm-muted">
              <span>주문 가능 <span className="num text-dracula-fg">{fmtNum(cash)}원</span></span>
              <span>
                보유 <span className="num text-dracula-fg">{holding ? `${fmtNum(holding.quantity)}주 · 평균 ${fmtNum(holding.avgPrice)}` : "—"}</span>
                {holding && sellable < ownedQty && <span className="num ml-1">(미체결 매도 {fmtNum(ownedQty - sellable)}주)</span>}
                {holding && (
                  <span className={cn("num ml-1", holding.pnl >= 0 ? "text-up" : "text-down")}>
                    ({holding.pnlRate >= 0 ? "+" : ""}{holding.pnlRate.toFixed(2)}%)
                  </span>
                )}
              </span>
            </div>
          </div>

          <div className="flex flex-col gap-1">
            <div className="flex gap-2">
              <Field label="익절가" unit="원" placeholder="—" disabled inputClassName="text-up" />
              <Field label="손절가" unit="원" placeholder="—" disabled inputClassName="text-down" />
            </div>
            <span className="flex items-center gap-1.5 text-2xs text-tm-muted"><PreviewTag />체결 시 익절/손절 자동 등록은 모의투자에 아직 없습니다</span>
          </div>

          <div className="flex flex-col gap-[7px] rounded-lg bg-tm-inner px-3 py-2.5">
            <div className="flex items-center justify-between">
              <span className="text-2xs text-tm-muted">주문 전 리스크 체크</span>
              <button type="button" onClick={() => risk.mutate()} disabled={risk.isPending || !isValid} className="text-2xs font-semibold text-dracula-purple hover:underline disabled:opacity-40">
                {risk.isPending ? "점검 중..." : risk.data ? "다시 점검" : "점검하기"}
              </button>
            </div>
            {risk.isError && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{(risk.error as Error).message}</p>}
            {!risk.data && !risk.isError && <p className="m-0 text-xs text-tm-muted">내 리스크 한도(VaR·집중도·손실 한도)에 걸리는지 미리 확인합니다.</p>}
            {risk.data?.checks.map((c) => (
              <div key={c.rule} className="flex justify-between gap-2 text-xs" title={c.detail}>
                <span className="flex items-center gap-1.5 text-tm-soft">
                  <Icon name={c.passed ? "check" : "alert"} size={14} strokeWidth={c.passed ? 2.4 : 2} className={c.passed ? "text-dracula-green" : "text-dracula-orange"} />
                  {RULE_LABEL[c.rule] ?? c.rule}
                </span>
                <span className={cn("num", !c.passed && "text-dracula-orange")}>{ruleValue(c)}</span>
              </div>
            ))}
          </div>

          <div className="flex flex-col gap-2">
            <span className="text-2xs text-tm-muted">지금 기분은? (감정 태그)</span>
            <div className="flex flex-wrap gap-1.5">
              {EMOTION_TAGS.map((t) => (
                <Chip key={t.key} active={emotionKey === t.key} onClick={() => setEmotionKey((k) => (k === t.key ? null : t.key))}>
                  {t.key}
                </Chip>
              ))}
            </div>
          </div>

          {error && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{error}</p>}
          {quantity > max && (
            <p className="m-0 text-xs text-[#ff8a8a]">
              {isBuy ? `잔고 부족 (최대 ${max}주 가능)` : `매도 가능 수량 초과 (최대 ${max}주)`}
            </p>
          )}
          {isLimit && !limitValid && <p className="m-0 text-xs text-[#ff8a8a]">지정가를 0보다 크게 입력하세요</p>}

          <p className="m-0 text-center text-xs leading-normal text-tm-muted">
            {isLimit
              ? limitValid
                ? `${fmtNum(limitPrice)}원 지정가로 ${fmtNum(quantity)}주 ${isBuy ? "매수" : "매도"} · ${crossesNow ? "즉시 체결" : "가격 도달 시 체결"}(모의투자)`
                : "지정가를 입력하세요"
              : currentPrice > 0
                ? `${fmtNum(currentPrice)}원 시장가로 ${fmtNum(quantity)}주 ${isBuy ? "매수" : "매도"} · 즉시 체결(모의투자)`
                : "현재가를 불러오는 중입니다"}
          </p>
          <Btn kind={isBuy ? "buy" : "sell"} full className="h-[46px]" onClick={handleSubmit} disabled={!isValid || isPending || (!isLimit && currentPrice <= 0)}>
            {isPending ? "처리 중..." : `${stock.name} ${isBuy ? "매수" : "매도"}`}
          </Btn>
        </>
      )}
    </>
  );
}
