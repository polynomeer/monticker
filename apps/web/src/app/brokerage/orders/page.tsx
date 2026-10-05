"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { getAccessToken } from "@/services/auth";
import {
  isUnresolvedOrderStatus, useBrokerageAccount, useBrokerageBalance, useBrokerageOrders, useBrokerageSettlements,
  useSubmitBrokerageOrder, useTradingStatus,
} from "@/hooks/useBrokerage";
import { useStockChart } from "@/hooks/useStockChart";
import { ApiError } from "@/services/brokerage";
import StockChart from "@/components/stock/chart/StockChart";
import OrderProposalCard from "@/components/ai/OrderProposalCard";
import {
  Btn, BuySell, DataTable, Field, Icon, KV, LiveBadge, Notice, Panel, PanelRow, SelectBox, SymbolPill, TerminalPage, TitleBlock,
  fmtNum, type Column,
} from "@/components/terminal";
import { TradingHaltBanner } from "@/components/brokerage/TradingHaltBanner";
import { OrderActions, OrderStatusCell, orderPriceText } from "@/components/brokerage/OrderCells";
import { SettlementsTable } from "@/components/brokerage/SettlementsTable";
import {
  LiveNotice, LoginRequired, NoAccount, PagerButtons, SelectedStock, SkeletonRows, StockSearchBox, apiStat, maskAccount,
  sideClass, sideLabel, won, ORDER_STATUS, type StockHit,
} from "@/components/brokerage/shared";
import { brokerageProviderLabel } from "@/lib/brokerageProvider";
import { cn } from "@/lib/utils";
import type { BrokerageOrderResponse, BrokerageOrderSide, BrokerageOrderType } from "@monticker/types";

/** KRX 호가 단위(2023년 개편, 유가·코스닥 공통). 국내 6자리 종목 코드에만 적용한다. */
function krxTick(price: number) {
  if (price < 2_000) return 1;
  if (price < 5_000) return 5;
  if (price < 20_000) return 10;
  if (price < 50_000) return 50;
  if (price < 200_000) return 100;
  if (price < 500_000) return 500;
  return 1_000;
}
const NO_INDICATORS: never[] = [];
const isKrxSymbol = (s: string) => /^\d{5}[0-9A-Z]$/.test(s);

type CheckState = "ok" | "fail" | "warn" | "wait" | "na";
interface Check { label: string; state: CheckState; /** 필수 — 실패하면 확인 단계로 못 넘어간다 */ required: boolean; }

function CheckLine({ c }: { c: Check }) {
  const icon = {
    ok: <Icon name="check" size={14} strokeWidth={2.4} className="text-dracula-green" />,
    fail: <Icon name="x" size={14} strokeWidth={2.4} className="text-[#ff8a8a]" />,
    warn: <Icon name="alert" size={14} className="text-dracula-orange" />,
    wait: <Icon name="clock" size={14} className="text-tm-muted" />,
    na: <span className="w-3.5 text-center text-tm-muted">–</span>,
  }[c.state];
  const srState = { ok: "통과", fail: "실패", warn: "주의", wait: "확인 중", na: "해당 없음" }[c.state];
  return (
    <li className={cn("flex items-center gap-1.5 text-xs", c.state === "fail" ? "text-[#ff8a8a]" : c.state === "na" ? "text-tm-muted" : "text-tm-soft")}>
      <span className="grid w-3.5 place-items-center">{icon}</span>
      <span>{c.label}</span>
      <span className="sr-only">: {srState}{c.required ? "" : " (참고)"}</span>
      {!c.required && c.state !== "na" && <span className="ml-auto text-2xs text-tm-muted">참고</span>}
    </li>
  );
}

interface Draft {
  stock: StockHit;
  side: BrokerageOrderSide;
  orderType: BrokerageOrderType;
  quantity: number;
  limitPrice?: number;
  estimate: number;
}

const p2 = (n: number) => String(n).padStart(2, "0");
function fmtClock(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const time = `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`;
  return d.toDateString() === today.toDateString() ? time : `${p2(d.getMonth() + 1)}.${p2(d.getDate())} ${time.slice(0, 5)}`;
}

export default function BrokerageOrderPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [stock, setStock] = useState<StockHit | null>(null);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [side, setSide] = useState<BrokerageOrderSide>("BUY");
  const [orderType, setOrderType] = useState<BrokerageOrderType>("MARKET");
  const [quantity, setQuantity] = useState(1);
  const [limitPrice, setLimitPrice] = useState("");
  const [riskBlock, setRiskBlock] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [result, setResult] = useState<BrokerageOrderResponse | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [histTab, setHistTab] = useState<"orders" | "fills">("orders");
  const [histPage, setHistPage] = useState(0);
  const confirmRef = useRef<HTMLDivElement>(null);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: balance, isLoading: balanceLoading, isError: balanceError } = useBrokerageBalance(!!account);
  const { data: trading } = useTradingStatus(!!account);
  const submitOrder = useSubmitBrokerageOrder();
  const ordersQuery = useBrokerageOrders(histPage, !!account && histTab === "orders");
  const fillsQuery = useBrokerageSettlements(histPage, !!account && histTab === "fills");
  const { candles } = useStockChart(stock?.id ?? null, "1m");

  const selectStock = async (hit: StockHit) => {
    setStock(hit);
    setRiskBlock(null);
    setOrderError(null);
    setResult(null);
    const r = await fetch(`/api/stocks/${hit.id}/price`);
    const data = r.ok ? await r.json() : null;
    setCurrentPrice(data?.price ?? 0);
  };

  // 보유 종목의 "주문" 버튼(/brokerage/orders?symbol=005930)에서 들어오면 그 종목을 정확히 일치할 때만 선택해 둔다.
  useEffect(() => {
    const symbol = new URLSearchParams(window.location.search).get("symbol");
    if (!symbol) return;
    const controller = new AbortController();
    fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(r => (r.ok ? r.json() : []))
      .then((hits: StockHit[]) => { const hit = hits.find(h => h.symbol === symbol); if (hit) selectStock(hit); })
      .catch(() => {});
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 확인 단계는 "확인할 때의 주문"만 보낸다 — 확인 뒤에 폼을 하나라도 바꾸면 확인을 무효로 하고 다시 확인받는다.
  useEffect(() => { setDraft(null); }, [stock?.id, side, orderType, quantity, limitPrice]);

  const holding = balance?.holdings.find(h => h.symbol === stock?.symbol);
  const cash = balance?.cash ?? 0;
  const priceForEstimate = orderType === "LIMIT" ? Number(limitPrice) || 0 : currentPrice;
  const estimatedAmount = quantity * priceForEstimate;
  const maxQty = side === "BUY"
    ? (priceForEstimate > 0 ? Math.floor(cash / priceForEstimate) : 0)
    : (holding?.quantity ?? 0);

  // balance===undefined는 "0원"과 "아직 모름"을 구분할 수 없다 — 로딩/에러 중엔 maxQty를
  // 신뢰할 수 없으니 제출을 막는다(V-M7). 정상 로딩 완료 후에는 잔고/보유 수량 상한도 반영한다.
  const balanceReady = !balanceLoading && !balanceError;
  const qtyOk = Number.isInteger(quantity) && quantity > 0;
  const limitOk = orderType === "MARKET" || Number(limitPrice) > 0;
  const halted = !!trading?.halted;
  const isValid =
    !!stock &&
    qtyOk &&
    limitOk &&
    balanceReady &&
    quantity <= maxQty &&
    !halted;

  // 검증 체크리스트 — 필수 항목은 위 isValid와 같은 조건을 그대로 보여 준다. 호가 단위·±30%는 참고(증권사가 최종 판정).
  const lp = Number(limitPrice);
  const tick = stock && isKrxSymbol(stock.symbol) && currentPrice > 0 ? krxTick(orderType === "LIMIT" && lp > 0 ? lp : currentPrice) : null;
  const checks: Check[] = [
    { label: "수량이 1주 이상의 정수입니다", state: qtyOk ? "ok" : "fail", required: true },
    orderType === "LIMIT"
      ? { label: "지정가가 0원보다 큽니다", state: limitOk ? "ok" : "fail", required: true }
      : { label: "시장가 — 현재가로 금액을 추정합니다", state: currentPrice > 0 ? "ok" : "warn", required: false },
    {
      label: side === "BUY" ? "가용 현금 이내입니다" : "보유 수량 이내입니다",
      state: balanceLoading ? "wait" : balanceError ? "fail" : quantity <= maxQty ? "ok" : "fail",
      required: true,
    },
    {
      label: tick ? `호가 단위(${fmtNum(tick)}원)에 맞습니다` : "호가 단위",
      state: orderType !== "LIMIT" || !tick || !(lp > 0) ? "na" : lp % tick === 0 ? "ok" : "warn",
      required: false,
    },
    {
      label: "현재가 대비 ±30% 이내입니다",
      state: orderType !== "LIMIT" || currentPrice <= 0 || !(lp > 0) ? "na" : Math.abs(lp - currentPrice) / currentPrice <= 0.3 ? "ok" : "warn",
      required: false,
    },
  ];

  // 주문 가격선 — 매 렌더 새 배열을 넘기면 차트가 통째로 다시 그려지므로 값이 바뀔 때만 만든다.
  const hasStock = !!stock;
  const orderLines = useMemo(
    () => (hasStock && priceForEstimate > 0 && qtyOk
      ? [{ id: -1, price: priceForEstimate, side, label: `주문 미리보기 · ${sideLabel(side)} ${fmtNum(quantity)}주` }]
      : []),
    [hasStock, priceForEstimate, qtyOk, side, quantity],
  );

  useEffect(() => { if (draft) confirmRef.current?.focus(); }, [draft]);

  const openConfirm = () => {
    if (!stock || !isValid) return;
    setRiskBlock(null);
    setOrderError(null);
    setResult(null);
    setDraft({
      stock, side, orderType, quantity,
      limitPrice: orderType === "LIMIT" ? Number(limitPrice) : undefined,
      estimate: estimatedAmount,
    });
  };

  const handleSubmit = async () => {
    // 확인한 내용 그대로, 그리고 지금도 검증을 통과할 때만 보낸다.
    if (!draft || !isValid) return;
    setRiskBlock(null);
    setOrderError(null);
    setResult(null);
    try {
      const order = await submitOrder.mutateAsync({
        symbol: draft.stock.symbol,
        side: draft.side,
        orderType: draft.orderType,
        quantity: draft.quantity,
        limitPrice: draft.orderType === "LIMIT" ? draft.limitPrice : undefined,
      });
      setResult(order);
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        setRiskBlock(e.message);
      } else {
        setOrderError((e as Error).message);
      }
    } finally {
      setDraft(null);
    }
  };

  if (!isLoggedIn) return <LoginRequired title="실전 주문" message="실전투자를 이용하려면 로그인이 필요합니다." />;
  if (!accountLoading && !account) return <NoAccount title="실전 주문" message="주문을 넣으려면 먼저 증권사 계좌를 연동하세요." />;

  const stats = [
    { label: "현재가", value: currentPrice > 0 ? `${fmtNum(currentPrice)}원` : "—" },
    { label: "등락", value: "—" },
    { label: "호가 단위", value: tick ? `${fmtNum(tick)}원` : "—" },
    { label: "가용 현금", value: balanceError ? "조회 불가" : won(balance?.cash), tone: balanceError ? "text-dracula-orange" : undefined },
    { ...apiStat(account), label: "API" },
  ];


  const histCols: Column<BrokerageOrderResponse>[] = [
    { key: "no", header: "주문번호", cell: o => <span className="num text-tm-muted">{o.pgOrderId ?? "—"}</span> },
    { key: "at", header: "시각", cell: o => <span className="num text-tm-muted">{fmtClock(o.submittedAt)}</span> },
    { key: "sym", header: "종목", cell: o => <span className="font-medium">{o.symbol}</span> },
    { key: "side", header: "구분", cell: o => <span className={sideClass(o.side)}>{sideLabel(o.side)}{o.orderType === "LIMIT" ? " · 지정가" : " · 시장가"}</span> },
    { key: "price", header: "가격", align: "right", cell: o => <span className="num">{orderPriceText(o, n => fmtNum(n))}</span> },
    { key: "qty", header: "수량", align: "right", cell: o => <span className="num">{fmtNum(o.quantity)}</span> },
    { key: "filled", header: "체결", align: "right", cell: o => <span className="num">{isUnresolvedOrderStatus(o.status) ? "?" : fmtNum(o.filledQty)}</span> },
    { key: "status", header: "상태", cell: o => <OrderStatusCell o={o} /> },
    { key: "act", header: <span className="sr-only">작업</span>, align: "right", cell: o => <OrderActions o={o} /> },
  ];

  const resultTone = result
    ? isUnresolvedOrderStatus(result.status) ? "warn" : result.status === "REJECTED" ? "danger" : "ok"
    : "ok";

  return (
    <TerminalPage
      left={stock ? <SymbolPill name={stock.name} code={stock.symbol} /> : <TitleBlock title="실전 주문" crumb="실전투자" />}
      stats={stats}
      account={{ kind: "live" }}
    >
      <LiveNotice />
      <TradingHaltBanner enabled={!!account} note="미체결 주문 취소와 주문 내역 확인은 계속 가능합니다." />

      <PanelRow>
        {/* ── 주문 폼 ── */}
        <Panel tabs={["실전 주문"]} actions={[]} closable={false} className="flex-[0_1_320px]" bodyClassName="gap-2.5">
          <div className="flex items-center justify-between gap-2">
            <LiveBadge />
            <span className="text-xs text-tm-muted">
              가용 현금 <b className="num text-dracula-fg">{balanceLoading ? "조회 중" : balanceError ? "조회 불가" : won(cash)}</b>
            </span>
          </div>

          {stock
            ? <SelectedStock stock={stock} price={currentPrice} onClear={() => { setStock(null); setCurrentPrice(0); setResult(null); setRiskBlock(null); setOrderError(null); }} />
            : <StockSearchBox onSelect={selectStock} />}

          {stock && (
            <>
              <BuySell value={side} onChange={s => { setSide(s); setQuantity(1); }} />
              <SelectBox label="주문 유형" value={orderType} onChange={e => setOrderType(e.target.value as BrokerageOrderType)}>
                <option value="MARKET">시장가</option>
                <option value="LIMIT">지정가</option>
              </SelectBox>

              {orderType === "LIMIT" ? (
                <Field
                  className="flex-none"
                  label="지정가"
                  unit="원"
                  type="number"
                  inputMode="numeric"
                  min={0}
                  value={limitPrice}
                  onChange={e => setLimitPrice(e.target.value)}
                  placeholder={currentPrice > 0 ? `현재가 ${fmtNum(currentPrice)}` : "가격 입력"}
                />
              ) : (
                <KV k="현재가 (시장가 기준)" v={currentPrice > 0 ? `${fmtNum(currentPrice)}원` : "—"} />
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
                  step={1}
                  value={quantity}
                  onChange={e => setQuantity(Math.max(1, Math.floor(Number(e.target.value) || 1)))}
                />
                <button type="button" aria-label="수량 1 증가" onClick={() => setQuantity(q => q + 1)}
                  className="grid w-9 flex-none place-items-center rounded-lg bg-tm-raised text-dracula-fg hover:bg-tm-line2"><Icon name="plus" size={16} /></button>
              </div>
              {maxQty > 0 && (
                <div className="flex gap-1">
                  {[25, 50, 75, 100].map(pct => (
                    <button key={pct} type="button" onClick={() => setQuantity(Math.max(1, Math.floor(maxQty * pct / 100)))}
                      className="h-6 flex-1 rounded-md bg-tm-inner text-2xs text-tm-soft hover:bg-tm-raised">
                      {pct}%
                    </button>
                  ))}
                </div>
              )}
              <span className={cn("text-2xs", balanceError ? "text-dracula-orange" : "text-tm-muted")}>
                {balanceLoading
                  ? "잔고 조회 중..."
                  : balanceError
                  ? "잔고 조회 실패 — 새로고침 후 다시 시도해주세요."
                  : side === "BUY" ? `최대 매수 가능 ${fmtNum(maxQty)}주` : `보유 수량 ${fmtNum(holding?.quantity ?? 0)}주`}
              </span>
              {balanceReady && quantity > maxQty && (
                <span className="text-2xs text-[#ff8a8a]">{side === "BUY" ? "가용 현금을 초과했습니다." : "보유 수량을 초과했습니다."}</span>
              )}

              <ul className="m-0 flex list-none flex-col gap-1.5 rounded-lg bg-tm-inner px-3 py-2.5" aria-label="주문 검증">
                {checks.map(c => <CheckLine key={c.label} c={c} />)}
              </ul>

              <KV k="예상 주문 금액" v={priceForEstimate > 0 ? `${fmtNum(estimatedAmount)}원` : "—"} />
              <KV k="예상 수수료" v="증권사 기준" mono={false} />

              <Btn kind={side === "BUY" ? "buy" : "sell"} size="lg" full onClick={openConfirm} disabled={!isValid || submitOrder.isPending}>
                주문 확인
              </Btn>
              <span className="text-center text-2xs text-tm-muted">
                {halted ? "실거래 주문이 중단된 동안에는 주문을 보낼 수 없습니다." : "필수 검증을 모두 통과해야 확인 단계로 넘어가고, 확인한 뒤에만 증권사로 전송됩니다."}
              </span>
            </>
          )}
        </Panel>

        {/* ── 차트 ── */}
        <Panel tabs={["차트"]} actions={["expand"]} className="flex-[999_1_460px]">
          {stock ? (
            <StockChart candles={candles} height={300} orderLines={orderLines} enabledIndicators={NO_INDICATORS} />
          ) : (
            <div className="grid h-[300px] place-items-center rounded-lg bg-tm-inner text-13 text-tm-muted">종목을 선택하면 분봉 차트와 주문 가격선이 표시됩니다.</div>
          )}
        </Panel>

        {/* ── 주문 확인 ── */}
        <Panel tabs={["주문 확인"]} actions={[]} closable={false} className="flex-[1_1_320px]">
          {draft ? (
            <div
              ref={confirmRef}
              tabIndex={-1}
              role="region"
              aria-label="실제 주문 확인"
              className="flex flex-col gap-3.5 rounded-xl border-[1.5px] border-dracula-orange bg-tm-inner p-5 outline-none"
            >
              <div className="flex items-center gap-2.5">
                <Icon name="alert" size={20} className="text-dracula-orange" />
                <span className="text-base font-bold">실제 주문을 보낼까요?</span>
              </div>
              <div className="flex flex-col gap-2">
                <KV k="계좌" v={`${account ? brokerageProviderLabel(account.provider) : "—"} ${maskAccount(account?.accountNumber)}`} />
                <KV k="종목" v={`${draft.stock.name} ${draft.stock.symbol}`} mono={false} />
                <KV k="구분" v={`${sideLabel(draft.side)} · ${draft.orderType === "LIMIT" ? "지정가" : "시장가"}`} valueClassName={sideClass(draft.side)} mono={false} />
                <KV k="가격 × 수량" v={`${draft.orderType === "LIMIT" ? fmtNum(draft.limitPrice) : "시장가"} × ${fmtNum(draft.quantity)}주`} />
                <div className="h-px bg-tm-line" />
                <KV
                  k={draft.orderType === "LIMIT" ? "주문 금액" : "예상 주문 금액 (현재가 기준)"}
                  v={draft.estimate > 0 ? `${fmtNum(draft.estimate)}원` : "—"}
                />
              </div>
              <div className="flex gap-2">
                <Btn kind="ghost" size="lg" className="flex-1" onClick={() => setDraft(null)} disabled={submitOrder.isPending}>취소</Btn>
                <Btn kind={draft.side === "BUY" ? "buy" : "sell"} size="lg" className="flex-[2]" onClick={handleSubmit} disabled={!isValid || submitOrder.isPending}>
                  {submitOrder.isPending ? "전송 중..." : `${sideLabel(draft.side)} 주문 전송`}
                </Btn>
              </div>
              <span className="text-2xs text-tm-muted">전송 후 증권사 응답을 받지 못하면 ‘결과 확인 중’으로 표시되며 자동으로 재전송하지 않습니다.</span>
            </div>
          ) : (
            <div className="flex flex-col gap-2 rounded-xl border border-dashed border-tm-line2 p-5 text-13 text-tm-muted">
              <span className="font-semibold text-tm-soft">아직 확인할 주문이 없습니다</span>
              왼쪽에서 주문을 채우고 ‘주문 확인’을 누르면 여기에서 계좌·종목·가격·수량을 마지막으로 확인한 뒤 전송합니다.
            </div>
          )}

          {riskBlock && (
            <Notice tone="danger">
              <b className="text-[#ff8a8a]">리스크 게이트에 의해 주문이 차단되었습니다</b>
              <br />{riskBlock}
            </Notice>
          )}
          {orderError && (
            <Notice tone="warn">
              <b className="text-dracula-orange">주문 제출에 실패했습니다</b>
              <br />{orderError}
            </Notice>
          )}
          {result && !riskBlock && !orderError && (
            <Notice tone={resultTone}>
              <b>{ORDER_STATUS[result.status]?.label ?? result.status}</b>
              <br />
              {isUnresolvedOrderStatus(result.status)
                ? "증권사 응답을 받지 못해 체결 여부를 확인하고 있습니다. 확인이 끝날 때까지 같은 주문을 다시 내지 마세요."
                : result.status === "REJECTED"
                  ? (result.rejectReason ?? "증권사가 주문을 거부했습니다.")
                  : result.filledQty > 0 && result.avgFillPrice
                    ? `${fmtNum(result.filledQty)}주 @ ${fmtNum(result.avgFillPrice)}원 체결`
                    : "증권사에 주문이 접수되었습니다."}
            </Notice>
          )}

          {stock && (
            <OrderProposalCard
              stockId={stock.id}
              onApprove={setSide}
              disclaimer="이 제안은 투자자문이 아니며, 실제 계좌에 대한 참고용 정보입니다. 최종 투자 판단과 책임은 본인에게 있습니다."
            />
          )}
        </Panel>
      </PanelRow>

      {/* ── 주문 내역 / 체결 내역 ── */}
      <Panel
        tabs={[{ key: "orders", label: "주문 내역" }, { key: "fills", label: "체결 내역" }]}
        active={histTab}
        onTabChange={k => { setHistTab(k as "orders" | "fills"); setHistPage(0); }}
        actions={["refresh"]}
        onAction={() => (histTab === "orders" ? ordersQuery.refetch() : fillsQuery.refetch())}
        bodyClassName="px-1.5 pb-1.5 pt-1"
      >
        {histTab === "orders" ? (
          ordersQuery.isLoading ? <SkeletonRows /> : (
            <>
              <DataTable columns={histCols} rows={ordersQuery.data?.content ?? []} rowKey={o => o.id} minWidth={860} empty="주문 내역이 없습니다." />
              <PagerButtons page={histPage} totalPages={ordersQuery.data?.totalPages ?? 0} onChange={setHistPage} />
            </>
          )
        ) : (
          fillsQuery.isLoading ? <SkeletonRows /> : (
            <>
              <SettlementsTable rows={fillsQuery.data?.content ?? []} />
              <PagerButtons page={histPage} totalPages={fillsQuery.data?.totalPages ?? 0} onChange={setHistPage} />
            </>
          )
        )}
      </Panel>
      <p className="py-2 text-center text-xs text-tm-muted">실제 자금이 이동합니다. 제출 전 수량과 가격을 다시 확인하세요.</p>
    </TerminalPage>
  );
}
