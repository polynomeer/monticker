"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useBrokerageBalance, useBrokerageOrders, useConditionalOrders, useBrokerageSettlements } from "@/hooks/useBrokerage";
import {
  AutoGrid, BtnLink, ChgNum, DataTable, Icon, LiveBadge, Notice, Panel, PanelCol, PanelRow, Pill, Stat, StockCell, TerminalPage,
  fmtNum, fmtSigned, dirClass, type Column, type IconName,
} from "@/components/terminal";
import { OrderActions, OrderStatusCell, orderPriceText } from "@/components/brokerage/OrderCells";
import { COND_STATUS, ConditionalCancelButton, TRIGGER_LABEL } from "@/components/brokerage/ConditionalOrderRow";
import {
  LiveNotice, LoginRequired, NoAccount, PagerButtons, SkeletonRows, brokerageStats, fmtDateTime, maskAccount,
  sideClass, sideLabel, useSymbolQuotes, won,
} from "@/components/brokerage/shared";
import { SettlementsTable } from "@/components/brokerage/SettlementsTable";
import { brokerageProviderLabel } from "@/lib/brokerageProvider";
import type { BrokerageHolding, BrokerageOrderResponse, ConditionalOrderResponse } from "@monticker/types";

type TimelineEntry =
  | { kind: "REGULAR"; at: string; order: BrokerageOrderResponse }
  | { kind: "CONDITIONAL"; at: string; order: ConditionalOrderResponse };

const QUICK: { title: string; sub: string; icon: IconName; href: string }[] = [
  { title: "실전 주문", sub: "지정가·시장가 주문 · 확인 단계 포함", icon: "send", href: "/brokerage/orders" },
  { title: "조건부 주문", sub: "가격 조건 충족 시 자동 제출", icon: "target", href: "/brokerage/conditional-orders" },
  { title: "리밸런싱", sub: "목표 비중과 괴리 확인 후 직접 실행", icon: "pie", href: "/brokerage/rebalance" },
  { title: "연동 관리", sub: "API 키 재인증", icon: "key", href: "/brokerage/connect" },
];

export default function BrokerageDashboardPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [orderTab, setOrderTab] = useState<"orders" | "settlements">("orders");
  const [ordersPage, setOrdersPage] = useState(0);
  const [settlementsPage, setSettlementsPage] = useState(0);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const balanceQuery = useBrokerageBalance(!!account);
  const { data: balance, isLoading: balanceLoading, isError: balanceError, error: balanceErr } = balanceQuery;
  const ordersQuery = useBrokerageOrders(ordersPage, !!account && orderTab === "orders");
  const conditionalQuery = useConditionalOrders(ordersPage, !!account && orderTab === "orders");
  const settlementsQuery = useBrokerageSettlements(settlementsPage, !!account && orderTab === "settlements");
  const holdings = balance?.holdings ?? [];
  const names = useSymbolQuotes(holdings.map(h => h.symbol), false);

  if (!isLoggedIn) return <LoginRequired title="실전투자" message="실전투자를 이용하려면 로그인이 필요합니다." />;

  if (accountLoading) return (
    <TerminalPage title="실전투자" crumb="증권사 연동 (BYOK)" account={{ kind: "live" }}>
      <SkeletonRows n={4} />
    </TerminalPage>
  );

  if (!account) return (
    <NoAccount
      title="실전투자"
      message="BYOK(Bring Your Own Key) 방식으로 본인 명의의 증권사 API 키를 연동해 실전 주문을 체결할 수 있습니다."
    />
  );

  const orders = ordersQuery.data?.content ?? [];
  const conditionalOrders = conditionalQuery.data?.content ?? [];
  const settlements = settlementsQuery.data?.content ?? [];

  // 일반 주문과 조건부 주문은 별도 API(별도 페이지네이션)지만 같은 계좌·같은 돈이라
  // "지금 뭐가 대기 중인지" 확인에는 한 화면에서 시간순으로 같이 보여야 한다.
  // 모의투자(matching)는 완전히 다른 돈이라 의도적으로 여기 섞지 않는다.
  const timeline: TimelineEntry[] = [
    ...orders.map((order): TimelineEntry => ({ kind: "REGULAR", at: order.submittedAt, order })),
    ...conditionalOrders.map((order): TimelineEntry => ({ kind: "CONDITIONAL", at: order.createdAt, order })),
  ].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
  const ordersTotalPages = Math.max(ordersQuery.data?.totalPages ?? 0, conditionalQuery.data?.totalPages ?? 0);

  const holdingCols: Column<BrokerageHolding>[] = [
    { key: "name", header: "종목", cell: h => <StockCell name={names.get(h.symbol)?.name ?? h.symbol} code={h.symbol} /> },
    { key: "qty", header: "수량", align: "right", cell: h => <span className="num">{fmtNum(h.quantity)}</span> },
    { key: "avg", header: "평균단가", align: "right", cell: h => <span className="num">{fmtNum(h.avgPrice)}</span> },
    { key: "cur", header: "현재가", align: "right", cell: h => <span className="num">{fmtNum(h.currentPrice)}</span> },
    { key: "pnl", header: "평가손익", align: "right", cell: h => { const pnl = (h.currentPrice - h.avgPrice) * h.quantity; return <span className={`num ${dirClass(pnl)}`}>{fmtSigned(pnl)}</span>; } },
    { key: "rate", header: "수익률", align: "right", cell: h => <ChgNum value={h.avgPrice > 0 ? ((h.currentPrice - h.avgPrice) / h.avgPrice) * 100 : null} /> },
    { key: "act", header: <span className="sr-only">주문</span>, align: "right", cell: h => (
      <BtnLink href={`/brokerage/orders?symbol=${encodeURIComponent(h.symbol)}`} kind="soft" size="sm">주문</BtnLink>
    ) },
  ];

  const timelineCols: Column<TimelineEntry>[] = [
    { key: "at", header: "시각", cell: e => <span className="num text-tm-muted">{fmtDateTime(e.at)}</span> },
    { key: "sym", header: "종목", cell: e => <span className="font-medium">{e.order.symbol}</span> },
    { key: "side", header: "구분", cell: e => (
      <span className="flex items-center gap-1.5">
        {e.kind === "CONDITIONAL" && <Pill tone="orange">조건부 · {TRIGGER_LABEL[e.order.triggerType]}</Pill>}
        <span className={sideClass(e.order.side)}>{sideLabel(e.order.side)}</span>
      </span>
    ) },
    { key: "qty", header: "수량", align: "right", cell: e => <span className="num">{fmtNum(e.order.quantity)}</span> },
    { key: "price", header: "가격", align: "right", cell: e => (
      <span className="num">
        {e.kind === "REGULAR"
          ? orderPriceText(e.order, n => fmtNum(n))
          : `${fmtNum(e.order.triggerPrice)} 발동`}
      </span>
    ) },
    { key: "status", header: "상태", cell: e => {
      if (e.kind === "REGULAR") return <OrderStatusCell o={e.order} />;
      const m = COND_STATUS[e.order.status];
      return <Pill tone={m?.tone ?? "muted"}>{m?.label ?? e.order.status}</Pill>;
    } },
    { key: "act", header: <span className="sr-only">작업</span>, align: "right", cell: e =>
      e.kind === "REGULAR" ? <OrderActions o={e.order} /> : <ConditionalCancelButton o={e.order} /> },
  ];

  const updatedAt = balanceQuery.dataUpdatedAt ? new Date(balanceQuery.dataUpdatedAt).toLocaleTimeString("ko-KR", { hour12: false }) : null;

  return (
    <TerminalPage title="실전투자" crumb="증권사 연동 (BYOK)" stats={brokerageStats(account, balance, balanceError)} account={{ kind: "live" }}>
      <LiveNotice />
      <PanelRow>
        <PanelCol className="flex-[1_1_340px]">
          <Panel tabs={["계좌"]} actions={["refresh"]} onAction={() => balanceQuery.refetch()} closable={false}>
            <div className="flex items-center gap-3">
              <span className="grid h-10 w-10 flex-none place-items-center rounded-[10px] bg-tm-raised text-dracula-fg"><Icon name="bank" size={20} /></span>
              <div className="flex min-w-0 flex-col gap-0.5">
                <span className="font-bold">{brokerageProviderLabel(account.provider)}</span>
                <span className="num text-xs text-tm-muted">{account.accountType ? `${account.accountType} ` : ""}{maskAccount(account.accountNumber)}</span>
              </div>
              <span className="ml-auto"><LiveBadge /></span>
            </div>

            <div className="flex flex-wrap items-center gap-2 text-xs">
              {account.tokenValid
                ? <Pill tone="green">정상 연결</Pill>
                : <Pill tone="orange">재인증 필요</Pill>}
              <Pill tone={account.isActive ? "cyan" : "muted"}>{account.isActive ? "활성" : "비활성"}</Pill>
              {!account.tokenValid && (
                <Link href="/brokerage/connect" className="ml-auto inline-flex h-7 items-center rounded-lg border border-[#6b3a44] px-2.5 text-xs font-semibold text-[#ff8a8a] hover:bg-[#3d252b]">
                  재연동
                </Link>
              )}
            </div>

            {balanceLoading ? (
              <SkeletonRows n={2} />
            ) : balanceError ? (
              // 증권사 장애(503) 중엔 "0원"이 아니라 "조회 불가"를 보여 준다 — 이전엔 서버가 0원을 돌려줘 잔고가 사라진 것처럼 보였다
              <Notice tone="warn">
                <b className="text-dracula-orange">잔고를 확인할 수 없습니다</b>
                <br />
                {balanceErr instanceof Error ? balanceErr.message : "증권사 응답이 없습니다. 잠시 후 자동으로 다시 시도합니다."}
              </Notice>
            ) : (
              // 억 단위 금액이 큰 글씨로 두 개 나란히 들어가면 좁은 계좌 카드에서 겹친다 — 칸을 넓혀 줄바꿈시킨다
              <AutoGrid min={190}>
                <Stat big label="가용 현금" value={won(balance?.cash)} />
                <Stat big label="총 평가금액" value={won(balance?.totalEvaluated)} />
              </AutoGrid>
            )}
            <span className="text-2xs text-tm-muted">
              잔고는 증권사 API에서 조회합니다{updatedAt && !balanceError ? ` · 마지막 갱신 ${updatedAt}` : ""}
            </span>
          </Panel>

          <Panel tabs={["바로가기"]} actions={[]} closable={false}>
            {QUICK.map(q => (
              <Link key={q.href} href={q.href} className="flex items-center gap-3 rounded-[10px] bg-tm-inner p-3.5 text-dracula-fg hover:bg-tm-raised">
                <span className="grid h-9 w-9 flex-none place-items-center rounded-[10px] bg-tm-raised text-dracula-purple"><Icon name={q.icon} size={18} /></span>
                <span className="flex flex-1 flex-col gap-0.5">
                  <span className="font-semibold">{q.title}</span>
                  <span className="text-xs text-tm-muted">{q.sub}</span>
                </span>
                <Icon name="chevr" size={16} className="text-tm-muted" />
              </Link>
            ))}
          </Panel>
        </PanelCol>

        <PanelCol className="flex-[999_1_620px]">
          <Panel tabs={["보유 종목"]} actions={["refresh"]} onAction={() => balanceQuery.refetch()} bodyClassName="px-1.5 pb-1.5 pt-1">
            {balanceLoading ? <SkeletonRows /> : balanceError ? (
              <p className="px-3 py-8 text-center text-13 text-tm-muted">잔고를 확인할 수 없어 보유 종목을 표시하지 않습니다.</p>
            ) : (
              <DataTable columns={holdingCols} rows={holdings} rowKey={h => h.symbol} minWidth={720} empty="보유 중인 종목이 없습니다." />
            )}
          </Panel>

          <Panel
            tabs={[{ key: "orders", label: "최근 주문" }, { key: "settlements", label: "정산 내역" }]}
            active={orderTab}
            onTabChange={k => setOrderTab(k as "orders" | "settlements")}
            closable={false}
            actions={["refresh"]}
            onAction={() => { if (orderTab === "orders") { ordersQuery.refetch(); conditionalQuery.refetch(); } else settlementsQuery.refetch(); }}
            bodyClassName="px-1.5 pb-2.5 pt-1"
          >
            {orderTab === "orders" ? (
              // 일반 주문 + 조건부 주문을 시간순으로 합쳐서 보여준다. 모의투자(matching)는 별도 계좌(가상 자금)라 섞지 않는다.
              <>
                {(ordersQuery.isLoading || conditionalQuery.isLoading) ? <SkeletonRows /> : (
                  <DataTable
                    columns={timelineCols}
                    rows={timeline}
                    rowKey={e => `${e.kind}-${e.order.id}`}
                    minWidth={760}
                    empty="주문 내역이 없습니다."
                  />
                )}
                <PagerButtons page={ordersPage} totalPages={ordersTotalPages} onChange={setOrdersPage} />
                <Notice tone="info">
                  ‘결과 확인 중’은 증권사 응답이 지연되어 체결 여부를 아직 확정하지 못한 주문입니다. 같은 주문을 다시 내기 전에 증권사 앱에서 확인하세요.
                  {" "}조건부 주문만 따로 관리하려면 <Link href="/brokerage/conditional-orders" className="underline">조건부 주문</Link>에서 확인하세요.
                </Notice>
              </>
            ) : (
              <>
                {settlementsQuery.isLoading ? <SkeletonRows /> : (
                  <SettlementsTable rows={settlements} />
                )}
                <PagerButtons page={settlementsPage} totalPages={settlementsQuery.data?.totalPages ?? 0} onChange={setSettlementsPage} />
              </>
            )}
          </Panel>
        </PanelCol>
      </PanelRow>
      <p className="py-2 text-center text-xs text-tm-muted">실제 자금이 이동하는 실전투자 계좌입니다. 투자에 대한 책임은 본인에게 있습니다.</p>
    </TerminalPage>
  );
}
