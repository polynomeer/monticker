"use client";

import { useEffect, useState } from "react";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useConditionalOrders, useCreateConditionalOrder, useCreateOcoOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { ApiError } from "@/services/brokerage";
import {
  Bar, Btn, BuySell, DataTable, Field, Icon, Notice, Panel, PanelCol, PanelRow, Pill, PreviewTag, Seg, SelectBox, StockCell, TerminalPage,
  fmtNum, type Column,
} from "@/components/terminal";
import {
  COND_STATUS, ConditionalCancelButton, ConditionalStatusCell, TRIGGER_LABEL, TRIGGER_TONE, isBelowTrigger,
} from "@/components/brokerage/ConditionalOrderRow";
import { TradingHaltBanner } from "@/components/brokerage/TradingHaltBanner";
import {
  LiveNotice, LoginRequired, NoAccount, PagerButtons, SelectedStock, SkeletonRows, StockSearchBox, fmtDateTime, sideLabel,
  useSymbolQuotes, type StockHit,
} from "@/components/brokerage/shared";
import type { BrokerageOrderSide, BrokerageOrderType, ConditionalOrderResponse, ConditionalTriggerType } from "@monticker/types";

// V-L7 — 주문 폼에 수량 상한이 전혀 없어 1e9 같은 값이 클라 검사를 그대로 통과했다.
const MAX_ORDER_QUANTITY = 1_000_000;

const TRIGGER_OPTIONS: { value: ConditionalTriggerType; label: string }[] = [
  { value: "STOP_LOSS", label: "손절 (이하 발동)" },
  { value: "PRICE_ABOVE", label: "돌파 (이상 발동)" },
  { value: "TAKE_PROFIT", label: "익절 (이상 발동)" },
  { value: "PRICE_BELOW", label: "하락 (이하 발동)" },
];

const DAY = 86_400_000;

export default function ConditionalOrderPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [stock, setStock] = useState<StockHit | null>(null);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [mode, setMode] = useState<"SINGLE" | "OCO">("SINGLE");
  const [side, setSide] = useState<BrokerageOrderSide>("SELL");
  const [quantity, setQuantity] = useState(1);
  const [triggerType, setTriggerType] = useState<ConditionalTriggerType>("STOP_LOSS");
  const [triggerPrice, setTriggerPrice] = useState("");
  const [orderType, setOrderType] = useState<BrokerageOrderType>("MARKET");
  const [limitPrice, setLimitPrice] = useState("");
  const [stopLossPrice, setStopLossPrice] = useState("");
  const [takeProfitPrice, setTakeProfitPrice] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const ordersQuery = useConditionalOrders(page, !!account);
  const { data: ordersData, isLoading: ordersLoading } = ordersQuery;
  const createSingle = useCreateConditionalOrder();
  const createOco = useCreateOcoOrder();
  const { toast } = useToast();

  const orders = ordersData?.content ?? [];
  const active = orders.filter(o => o.status === "ACTIVE");
  const history = orders.filter(o => o.status !== "ACTIVE");
  const quotes = useSymbolQuotes(active.map(o => o.symbol), true);

  const selectStock = async (hit: StockHit) => {
    setStock(hit);
    setFormError(null);
    const r = await fetch(`/api/stocks/${hit.id}/price`);
    const data = r.ok ? await r.json() : null;
    setCurrentPrice(data?.price ?? 0);
  };

  // ADR-032 — 방향이 반전된 트리거는 등록 즉시 "이미 참"이라 다음 틱에 바로 발동된다
  // (ConditionalTriggerType.isTriggered: STOP_LOSS/PRICE_BELOW는 현재가<=trigger,
  // TAKE_PROFIT/PRICE_ABOVE는 현재가>=trigger). 현재가를 아직 못 구했으면(0) 판단 근거가
  // 없으니 막지 않는다 — 그 경우는 triggerPrice>0 검사만으로 최소한의 방어가 이미 있다.
  const isBelowType = isBelowTrigger(triggerType);
  const singleDirectionValid = currentPrice <= 0 || Number(triggerPrice) <= 0 ||
    (isBelowType ? Number(triggerPrice) < currentPrice : Number(triggerPrice) > currentPrice);
  const ocoDirectionValid = currentPrice <= 0 ||
    ((Number(stopLossPrice) <= 0 || Number(stopLossPrice) < currentPrice) &&
     (Number(takeProfitPrice) <= 0 || Number(takeProfitPrice) > currentPrice));

  const isSingleValid = !!stock && quantity > 0 && Number(triggerPrice) > 0 && (orderType === "MARKET" || Number(limitPrice) > 0) && singleDirectionValid;
  const isOcoValid = !!stock && quantity > 0 && Number(stopLossPrice) > 0 && Number(takeProfitPrice) > 0 && ocoDirectionValid;
  const isPending = createSingle.isPending || createOco.isPending;

  const handleSubmitSingle = async () => {
    if (!stock) return;
    setFormError(null);
    try {
      await createSingle.mutateAsync({
        symbol: stock.symbol,
        side,
        quantity,
        leg: {
          triggerType,
          triggerPrice: Number(triggerPrice),
          orderType,
          limitPrice: orderType === "LIMIT" ? Number(limitPrice) : undefined,
        },
      });
      toast({ type: "success", title: "등록 완료", message: "조건부 주문이 등록되었습니다." });
      setTriggerPrice("");
      setLimitPrice("");
    } catch (e) {
      setFormError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handleSubmitOco = async () => {
    if (!stock) return;
    setFormError(null);
    try {
      await createOco.mutateAsync({
        symbol: stock.symbol,
        side,
        quantity,
        legs: [
          { triggerType: "STOP_LOSS", triggerPrice: Number(stopLossPrice), orderType: "MARKET" },
          { triggerType: "TAKE_PROFIT", triggerPrice: Number(takeProfitPrice), orderType: "MARKET" },
        ],
      });
      toast({ type: "success", title: "OCO 등록 완료", message: "손절/익절 조건이 등록되었습니다." });
      setStopLossPrice("");
      setTakeProfitPrice("");
    } catch (e) {
      setFormError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  if (!isLoggedIn) return <LoginRequired title="조건부 주문" message="조건부 주문을 이용하려면 로그인이 필요합니다." />;
  if (!accountLoading && !account) return <NoAccount title="조건부 주문" message="조건부 주문을 등록하려면 먼저 증권사 계좌를 연동하세요." />;

  // "트리거까지" — 현재가가 트리거에 얼마나 가까운지. 현재가를 모르면 표시하지 않는다.
  const distancePct = (o: ConditionalOrderResponse) => {
    const cur = quotes.get(o.symbol)?.price;
    if (!cur) return null;
    return (Math.abs(o.triggerPrice - cur) / cur) * 100;
  };
  const imminent = active.filter(o => { const d = distancePct(o); return d != null && d < 2; }).length;
  const now = new Date();
  const firedThisMonth = orders.filter(o => {
    if (!o.triggeredAt) return false;
    const t = new Date(o.triggeredAt);
    return t.getFullYear() === now.getFullYear() && t.getMonth() === now.getMonth();
  }).length;
  const singlePage = (ordersData?.totalPages ?? 0) <= 1;

  const activeCols: Column<ConditionalOrderResponse>[] = [
    { key: "name", header: "종목", cell: o => <StockCell name={quotes.get(o.symbol)?.name ?? o.symbol} code={o.symbol} /> },
    { key: "type", header: "유형", cell: o => (
      <span className="flex items-center gap-1.5">
        <Pill tone={TRIGGER_TONE[o.triggerType]}>{TRIGGER_LABEL[o.triggerType]}</Pill>
        <span className={o.side === "BUY" ? "text-up" : "text-down"}>{sideLabel(o.side)}</span>
      </span>
    ) },
    { key: "trig", header: "트리거", align: "right", cell: o => <span className="num">{fmtNum(o.triggerPrice)} {isBelowTrigger(o.triggerType) ? "이하" : "이상"}</span> },
    { key: "cur", header: "현재가", align: "right", cell: o => <span className="num">{fmtNum(quotes.get(o.symbol)?.price)}</span> },
    { key: "dist", header: "트리거까지", cell: o => {
      const d = distancePct(o);
      if (d == null) return <span className="text-tm-muted">—</span>;
      return (
        <div className="flex min-w-[150px] items-center gap-2">
          <div className="flex-1"><Bar pct={100 - d * 10} color={d < 2 ? "bg-dracula-orange" : "bg-dracula-purple"} h={6} /></div>
          <span className="num text-xs">{d.toFixed(1)}%</span>
        </div>
      );
    } },
    { key: "limit", header: "지정가", align: "right", cell: o => <span className="num">{o.orderType === "LIMIT" ? fmtNum(o.limitPrice) : "시장가"}</span> },
    { key: "qty", header: "수량", align: "right", cell: o => <span className="num">{fmtNum(o.quantity)}</span> },
    { key: "status", header: "상태", cell: o => <ConditionalStatusCell o={o} /> },
    { key: "act", header: <span className="sr-only">해지</span>, align: "right", cell: o => <ConditionalCancelButton o={o} /> },
  ];

  const historyCols: Column<ConditionalOrderResponse>[] = [
    { key: "at", header: "시각", cell: o => <span className="num text-tm-muted">{fmtDateTime(o.triggeredAt ?? o.createdAt)}</span> },
    { key: "desc", header: "내용", cell: o => (
      <span className="whitespace-normal">
        {o.symbol} {TRIGGER_LABEL[o.triggerType]} {fmtNum(o.triggerPrice)}{isBelowTrigger(o.triggerType) ? " 이하" : " 이상"}
        {" → "}{sideLabel(o.side)} {fmtNum(o.quantity)}주{o.orderType === "LIMIT" ? ` (지정가 ${fmtNum(o.limitPrice)})` : " (시장가)"}
        {o.failReason && <span className="block text-2xs text-[#ff8a8a]">{o.failReason}</span>}
      </span>
    ) },
    { key: "res", header: "결과", cell: o => { const m = COND_STATUS[o.status]; return <Pill tone={m?.tone ?? "muted"}>{m?.label ?? o.status}</Pill>; } },
  ];

  // 등록 전 요약 — 입력값 그대로 "무엇이 언제 실계좌로 나가는지" 문장으로 보여 준다.
  const tp = Number(triggerPrice);
  const summary = mode === "SINGLE"
    ? tp > 0
      ? <>현재가가 <b className="num">{fmtNum(tp)}원</b> {isBelowType ? "이하로 떨어지면" : "이상으로 오르면"} {orderType === "LIMIT" && Number(limitPrice) > 0 ? <><b className="num">{fmtNum(Number(limitPrice))}원</b> 지정가</> : "시장가"} {sideLabel(side)} {fmtNum(quantity)}주 주문이 <b>실계좌로 자동 제출</b>됩니다.</>
      : <>트리거 가격을 입력하면 조건이 충족됐을 때 <b>실계좌로 자동 제출</b>될 주문을 여기에 요약합니다.</>
    : <>손절가 이하로 떨어지거나 익절가 이상으로 오르면 시장가 {sideLabel(side)} {fmtNum(quantity)}주가 <b>실계좌로 자동 제출</b>되고, 나머지 하나는 자동으로 취소됩니다.</>;

  return (
    <TerminalPage
      title="조건부 주문"
      crumb="실전투자 · 가격 조건 충족 시 자동 제출"
      stats={[
        { label: "감시 중", value: `${active.length}건${singlePage ? "" : "+"}` },
        { label: "트리거 임박", value: `${imminent}건`, tone: imminent > 0 ? "text-dracula-orange" : undefined },
        { label: "이번 달 발동", value: singlePage ? `${firedThisMonth}건` : "—" },
        { label: "감시 주기", value: "실시간 시세" },
      ]}
      account={{ kind: "live" }}
    >
      <LiveNotice />
      <TradingHaltBanner enabled={!!account} note="걸어둔 조건부 주문은 그대로 유지되며, 중단이 해제되면 자동으로 다시 감시합니다." />

      <PanelRow>
        <Panel tabs={["조건부 주문 등록"]} actions={[]} closable={false} className="flex-[0_1_360px] self-start" bodyClassName="gap-2.5">
          {stock
            ? <SelectedStock stock={stock} price={currentPrice} onClear={() => { setStock(null); setCurrentPrice(0); setFormError(null); }} />
            : <StockSearchBox onSelect={selectStock} />}

          {stock && (
            <>
              <BuySell value={side} onChange={setSide} />
              <Seg
                full
                options={[{ value: "SINGLE", label: "단일 조건" }, { value: "OCO", label: "OCO (손절+익절)" }]}
                value={mode}
                onChange={m => { setMode(m); setFormError(null); }}
              />

              {mode === "SINGLE" ? (
                <>
                  <div className="grid grid-cols-2 gap-0.5 rounded-lg bg-tm-inner p-[3px]" role="group" aria-label="트리거 조건">
                    {TRIGGER_OPTIONS.map(t => (
                      <button key={t.value} type="button" aria-pressed={triggerType === t.value} onClick={() => setTriggerType(t.value)}
                        className={`h-[30px] rounded-md px-2 text-xs ${triggerType === t.value ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg"}`}>
                        {t.label}
                      </button>
                    ))}
                  </div>
                  <Field
                    className="flex-none"
                    label="트리거 가격"
                    unit="원"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    value={triggerPrice}
                    onChange={e => setTriggerPrice(e.target.value)}
                    placeholder={currentPrice > 0 ? `현재가 ${fmtNum(currentPrice)}` : "가격 입력"}
                    inputClassName={isBelowType ? "text-down" : "text-up"}
                  />
                  {!singleDirectionValid && (
                    <span className="text-xs text-dracula-orange">
                      {isBelowType
                        ? `현재가(${fmtNum(currentPrice)}원)보다 낮아야 합니다 — 이대로면 등록 즉시 발동됩니다.`
                        : `현재가(${fmtNum(currentPrice)}원)보다 높아야 합니다 — 이대로면 등록 즉시 발동됩니다.`}
                    </span>
                  )}
                  <SelectBox label="발동 시 주문 유형" value={orderType} onChange={e => setOrderType(e.target.value as BrokerageOrderType)}>
                    <option value="MARKET">시장가</option>
                    <option value="LIMIT">지정가</option>
                  </SelectBox>
                  {orderType === "LIMIT" && (
                    <Field className="flex-none" label="지정가" unit="원" type="number" inputMode="numeric" min={0} value={limitPrice} onChange={e => setLimitPrice(e.target.value)} />
                  )}
                </>
              ) : (
                <>
                  <span className="text-xs leading-relaxed text-tm-muted">
                    손절가와 익절가를 동시에 등록합니다 — 하나가 발동되면 나머지는 자동으로 취소되고, 발동 시 시장가로 제출됩니다.
                  </span>
                  <Field
                    className="flex-none"
                    label="손절가 (이 가격 이하로 떨어지면 발동)"
                    unit="원"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    value={stopLossPrice}
                    onChange={e => setStopLossPrice(e.target.value)}
                    placeholder={currentPrice > 0 ? `현재가 ${fmtNum(currentPrice)}보다 낮게` : "가격 입력"}
                    inputClassName="text-down"
                  />
                  {currentPrice > 0 && Number(stopLossPrice) > 0 && Number(stopLossPrice) >= currentPrice && (
                    <span className="text-xs text-dracula-orange">현재가({fmtNum(currentPrice)}원)보다 낮아야 합니다 — 이대로면 등록 즉시 발동됩니다.</span>
                  )}
                  <Field
                    className="flex-none"
                    label="익절가 (이 가격 이상 오르면 발동)"
                    unit="원"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    value={takeProfitPrice}
                    onChange={e => setTakeProfitPrice(e.target.value)}
                    placeholder={currentPrice > 0 ? `현재가 ${fmtNum(currentPrice)}보다 높게` : "가격 입력"}
                    inputClassName="text-up"
                  />
                  {currentPrice > 0 && Number(takeProfitPrice) > 0 && Number(takeProfitPrice) <= currentPrice && (
                    <span className="text-xs text-dracula-orange">현재가({fmtNum(currentPrice)}원)보다 높아야 합니다 — 이대로면 등록 즉시 발동됩니다.</span>
                  )}
                </>
              )}

              <div className="flex items-stretch gap-1.5">
                <button type="button" aria-label="수량 1 감소" onClick={() => setQuantity(q => Math.max(1, q - 1))}
                  className="grid w-9 flex-none place-items-center rounded-lg bg-tm-raised text-dracula-fg hover:bg-tm-line2"><Icon name="minus" size={16} /></button>
                <Field
                  label="수량"
                  unit="주"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MAX_ORDER_QUANTITY}
                  value={quantity}
                  onChange={e => setQuantity(Math.min(MAX_ORDER_QUANTITY, Math.max(1, Math.floor(Number(e.target.value) || 1))))}
                />
                <button type="button" aria-label="수량 1 증가" onClick={() => setQuantity(q => Math.min(MAX_ORDER_QUANTITY, q + 1))}
                  className="grid w-9 flex-none place-items-center rounded-lg bg-tm-raised text-dracula-fg hover:bg-tm-line2"><Icon name="plus" size={16} /></button>
              </div>

              <div className="flex flex-col gap-1">
                <SelectBox label="유효 기간" disabled value="90">
                  <option value="90">90일 ({new Date(Date.now() + 90 * DAY).toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" })}까지) · 자동 만료</option>
                </SelectBox>
                <span className="flex items-center gap-1.5 text-2xs text-tm-muted">기간 선택 <PreviewTag /></span>
              </div>

              <Notice tone="warn" icon="alert">{summary}</Notice>

              {formError && (
                <Notice tone="danger">
                  <b className="text-[#ff8a8a]">등록에 실패했습니다</b>
                  <br />{formError}
                </Notice>
              )}

              <Btn
                size="lg"
                full
                onClick={mode === "SINGLE" ? handleSubmitSingle : handleSubmitOco}
                disabled={!(mode === "SINGLE" ? isSingleValid : isOcoValid) || isPending}
              >
                {isPending ? "등록 중..." : "조건부 주문 등록"}
              </Btn>
            </>
          )}
        </Panel>

        <PanelCol className="flex-[999_1_600px]">
          <Panel tabs={[`감시 중 ${active.length}`]} actions={["refresh"]} onAction={() => ordersQuery.refetch()} bodyClassName="px-1.5 pb-1.5 pt-1">
            {ordersLoading ? <SkeletonRows n={2} /> : (
              <DataTable columns={activeCols} rows={active} rowKey={o => o.id} minWidth={900} empty="감시 중인 조건부 주문이 없습니다." />
            )}
          </Panel>
          <Panel tabs={["발동 기록"]} actions={[]} bodyClassName="px-1.5 pb-1.5 pt-1">
            {ordersLoading ? <SkeletonRows n={2} /> : (
              <DataTable columns={historyCols} rows={history} rowKey={o => o.id} minWidth={600} empty="발동·해지·만료된 조건부 주문이 없습니다." />
            )}
            <PagerButtons page={page} totalPages={ordersData?.totalPages ?? 0} onChange={setPage} />
          </Panel>
          <p className="px-1 text-xs leading-relaxed text-tm-muted">
            같은 계좌를 HTS/앱 등 다른 경로로도 거래하면 조건부 주문이 이를 인지하지 못해 의도치 않은 중복 매매가 발생할 수 있습니다.
          </p>
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
