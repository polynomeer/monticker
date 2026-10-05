"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getBillingStatus, getOrCreateCustomerKey, deregisterBillingKey, type BillingStatus } from "@/services/billing";
import { isRealPaymentEnabled, openTossPaymentWindow, preparePayment } from "@/services/payment";
import { useToast } from "@/hooks/useToast";
import {
  Btn, DataTable, Divider, Icon, KV, Panel, PanelRow, Pill, PreviewTag, TerminalPage, type BtnKind, type Column,
} from "@/components/terminal";
import { cn } from "@/lib/utils";
import { nextBillingText } from "@/lib/subscriptionBilling";

interface Plan {
  id: number;
  code: "FREE" | "PRO" | "QUANT";
  name: string;
  price: number;
  currency: string;
  features: string[];
  isActive: boolean;
}

interface MySubscription {
  id: number;
  planCode: string;
  status: "ACTIVE" | "EXPIRED" | "CANCELLED";
  startedAt: string;
  expiresAt: string | null;
  cancelledAt: string | null;
  /** ADR-083 — 다음 정기결제 시각(갱신 잡 실행 시각). 청구되지 않으면 null */
  nextBillingAt?: string | null;
  nextBillingAmount?: number | null;
  noChargeReason?: "FREE_PLAN" | "CANCELLED" | "NOT_ACTIVE" | "NO_BILLING_KEY" | null;
}


interface PaymentRecord {
  id: number;
  planCode: string;
  amount: number;
  status: "PENDING" | "SUCCESS" | "FAILED" | "REFUNDED";
  pgProvider: string;
  paidAt: string | null;
  createdAt: string;
}

/** 플랜 코드별 대상 문구(시안 카피) — 가격·기능은 서버(/api/subscription/plans) 값을 쓴다 */
const PLAN_WHO: Record<string, string> = {
  FREE: "관찰을 시작하는 분",
  PRO: "이벤트 매매를 하는 분",
  QUANT: "전략을 만들고 파는 분",
};

const STATUS_LABELS: Record<string, { label: string; tone: string }> = {
  ACTIVE: { label: "활성", tone: "text-dracula-green" },
  EXPIRED: { label: "만료", tone: "text-[#ff8a8a]" },
  CANCELLED: { label: "해지", tone: "text-tm-muted" },
};

const PAYMENT_LABELS: Record<string, { label: string; tone: string }> = {
  SUCCESS: { label: "결제 완료", tone: "text-dracula-green" },
  FAILED: { label: "결제 실패", tone: "text-[#ff8a8a]" },
  PENDING: { label: "처리 중", tone: "text-dracula-orange" },
  REFUNDED: { label: "환불", tone: "text-tm-muted" },
};

const FAQ: { q: string; a: string }[] = [
  { q: "실전투자 연동도 구독에 포함되나요?", a: "증권사 API 키를 직접 등록해 사용하는 방식(BYOK)입니다. 플랜별 이용 범위는 확정 전이며, 증권사 수수료는 별도입니다." },
  { q: "전략 마켓 판매 수익은 어떻게 받나요?", a: "전략 마켓의 제작자 수익 화면에서 정산 내역을 확인할 수 있습니다. 출금 방식은 확정 전입니다." },
  { q: "환불 정책은?", a: "[환불 정책 — 법률 검토 후 확정]" },
];

function fmtDate(iso: string | null | undefined) {
  return iso ? new Date(iso).toLocaleDateString("ko-KR") : "—";
}

function PlanCard({
  plan, isCurrent, onSubscribe, isPending,
}: { plan: Plan; isCurrent: boolean; onSubscribe: (code: string) => void; isPending: boolean }) {
  const hot = plan.code === "PRO";
  const free = plan.price === 0;
  const kind: BtnKind = isCurrent ? "soft" : hot ? "primary" : free ? "soft" : "ghost";
  const label = isCurrent ? "현재 플랜" : isPending ? "처리 중..." : free ? "무료로 시작" : `${plan.code} 시작하기`;
  return (
    <section className={cn("flex min-w-0 flex-col gap-4 rounded-[14px] bg-tm-panel p-[22px]", hot && "outline outline-2 outline-dracula-purple")}>
      <div className="flex items-center justify-between gap-2">
        <span className={cn("font-bold tracking-[0.06em]", hot ? "text-dracula-purple" : "text-tm-soft")}>{plan.code}</span>
        <span className="flex gap-1.5">
          {isCurrent && <Pill tone="green">이용 중</Pill>}
          {hot && <Pill tone="purple">추천</Pill>}
        </span>
      </div>
      <div className="flex flex-col gap-1">
        <span className="num text-[1.625rem] font-semibold">
          {free ? "무료" : `${plan.price.toLocaleString("ko-KR")}원`}
          {!free && <span className="text-13 text-tm-muted"> / 월</span>}
        </span>
        <span className="text-13 text-tm-muted">{PLAN_WHO[plan.code] ?? plan.name}</span>
      </div>
      <ul className="m-0 flex flex-1 list-none flex-col gap-2.5 p-0">
        {(plan.features ?? []).map((f, i) => (
          <li key={i} className="flex gap-2.5 text-13 text-tm-soft">
            <Icon name="check" size={16} strokeWidth={2.4} className="mt-px flex-none text-dracula-green" />
            {f}
          </li>
        ))}
      </ul>
      <Btn kind={kind} size="lg" full onClick={() => onSubscribe(plan.code)} disabled={isPending || isCurrent}>
        {label}
      </Btn>
    </section>
  );
}

const PAYMENT_COLUMNS: Column<PaymentRecord>[] = [
  { key: "date", header: "일자", cell: (p) => <span className="num">{fmtDate(p.createdAt)}</span> },
  { key: "plan", header: "플랜", cell: (p) => `${p.planCode} 플랜` },
  { key: "pg", header: "결제사", cell: (p) => p.pgProvider },
  {
    key: "status", header: "상태",
    cell: (p) => {
      const m = PAYMENT_LABELS[p.status] ?? { label: p.status, tone: "text-dracula-fg" };
      return <span className={m.tone}>{m.label}</span>;
    },
  },
  { key: "amount", header: "금액", align: "right", cell: (p) => <span className="num">{p.amount.toLocaleString("ko-KR")}원</span> },
];

export default function SubscriptionPage() {
  const [showHistory, setShowHistory] = useState(false);
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: plansData, isLoading: plansLoading } = useQuery<Plan[]>({
    queryKey: ["subscription", "plans"],
    queryFn: () => authFetch("/api/subscription/plans").then(r => r.json()),
  });

  const { data: mySub } = useQuery<MySubscription>({
    queryKey: ["subscription", "me"],
    queryFn: async () => {
      const res = await authFetch("/api/subscription/me");
      if (res.status === 404) return null;
      return res.json();
    },
  });

  const { data: paymentsData, isLoading: paymentsLoading } = useQuery<PaymentRecord[]>({
    queryKey: ["subscription", "payments"],
    queryFn: () => authFetch("/api/subscription/payments").then(r => r.json()),
    enabled: showHistory,
  });

  const { data: billingStatus, isLoading: billingLoading } = useQuery<BillingStatus>({
    queryKey: ["subscription", "billing"],
    queryFn: getBillingStatus,
  });

  const registerCardMutation = useMutation({
    mutationFn: async () => {
      const clientKey = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;
      if (!clientKey) throw new Error("결제 설정이 올바르지 않습니다. 잠시 후 다시 시도해주세요.");

      const customerKey = await getOrCreateCustomerKey();
      const { loadTossPayments } = await import("@tosspayments/tosspayments-sdk");
      const tossPayments = await loadTossPayments(clientKey);
      const payment = tossPayments.payment({ customerKey });

      // 성공/실패 모두 같은 콜백 페이지로 리다이렉트된다 — 토스가 성공 시 authKey/customerKey를,
      // 실패 시 code/message를 쿼리 파라미터로 붙여준다(callback 페이지가 구분해서 처리).
      await payment.requestBillingAuth({
        method: "CARD",
        successUrl: `${window.location.origin}/subscription/billing/callback`,
        failUrl: `${window.location.origin}/subscription/billing/callback`,
      });
      // requestBillingAuth는 페이지를 토스로 리다이렉트한다 — 여기 이후 코드는 실행되지 않는다.
    },
    onError: (e: Error) => toast({ type: "error", title: "카드 등록 실패", message: e.message }),
  });

  const deregisterCardMutation = useMutation({
    mutationFn: deregisterBillingKey,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["subscription", "billing"] });
      qc.invalidateQueries({ queryKey: ["subscription", "me"] });   // 카드가 없으면 다음 결제 예정도 사라진다
      toast({ type: "success", title: "해지 완료", message: "자동결제 카드가 해지되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "해지 실패", message: e.message }),
  });

  const subscribeMutation = useMutation({
    mutationFn: async (planCode: string) => {
      const plan = plansData?.find(p => p.code === planCode);

      // 유료 플랜 + 실결제 모드면 prepare → 토스 SDK → 콜백의 confirm 으로 간다 (ADR-059).
      // 금액과 orderId 는 서버가 정한다 — 이 함수는 그 값을 SDK 에 넘기기만 하고 직접 만들지
      // 않는다. 무료 플랜과 Mock PG 모드는 결제가 없으니 기존 /subscribe 를 그대로 쓴다
      // (운영 PG 모드에서 /subscribe 는 TossPgClient.requestPayment 스텁 때문에 항상 실패한다).
      if (plan && plan.price > 0 && isRealPaymentEnabled()) {
        const prepared = await preparePayment(planCode);
        await openTossPaymentWindow(prepared, plan.name);
        // 결제창이 열리면 브라우저가 토스로 떠난다 — 아래 onSuccess 는 실행되지 않는다.
        return null;
      }

      const r = await authFetch("/api/subscription/subscribe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ planCode }),
      });
      if (!r.ok) throw new Error(await r.text());
      return r.json();
    },
    onSuccess: (result) => {
      // 토스 결제창으로 떠나는 경로는 여기 오지 않는다(리다이렉트). 와도 토스트는 띄우지 않는다.
      if (result === null) return;
      qc.invalidateQueries({ queryKey: ["subscription"] });
      toast({ type: "success", title: "구독 완료", message: "플랜이 변경되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "구독 실패", message: e.message }),
  });

  const cancelMutation = useMutation({
    mutationFn: () =>
      authFetch("/api/subscription/cancel", { method: "POST" }).then(async r => {
        if (!r.ok) throw new Error(await r.text());
        return null;   // 204 No Content — 본문이 없다(r.json()은 실패해 성공한 해지를 "해지 실패"로 보였다)
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["subscription"] });
      toast({ type: "success", title: "해지 완료", message: "구독이 해지되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "해지 실패", message: e.message }),
  });

  const currentCode = mySub?.status === "ACTIVE" ? mySub.planCode : null;
  const plans = plansData ?? [];

  return (
    <TerminalPage
      title="구독 플랜"
      crumb="계정"
      stats={[
        { label: "현재 플랜", value: currentCode ?? "—" },
        { label: "다음 결제일", value: nextBillingText(mySub), tone: mySub?.noChargeReason === "NO_BILLING_KEY" ? "text-dracula-orange" : undefined },
        { label: "결제 수단", value: billingStatus?.registered ? "토스페이먼츠" : "미등록" },
      ]}
    >
      <div className="flex flex-col gap-1.5 px-1.5 pb-1 pt-2">
        <h2 className="m-0 text-[1.375rem] font-bold">더 강력한 분석 도구로 업그레이드하세요</h2>
        <span className="text-tm-muted">언제든 해지할 수 있고, 해지해도 결제 기간이 끝날 때까지 이용할 수 있습니다.</span>
      </div>

      <div className="flex items-center gap-2 px-1.5">
        <div className="inline-flex gap-0.5 rounded-lg bg-tm-inner p-[3px]">
          <button type="button" aria-pressed className="h-[30px] whitespace-nowrap rounded-md bg-tm-line2 px-3 text-13 font-semibold text-dracula-fg">
            월간 결제
          </button>
          {/* 연간 결제는 서버에 요금제가 없다 — 시안 요소 */}
          <button type="button" aria-pressed={false} disabled title="준비 중" className="h-[30px] cursor-not-allowed whitespace-nowrap rounded-md px-3 text-13 text-tm-muted opacity-50">
            연간 결제 · 2개월 무료
          </button>
        </div>
        <PreviewTag />
      </div>

      {plansLoading ? (
        <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(260px,1fr))" }}>
          {[1, 2, 3].map((i) => <div key={i} className="h-80 animate-pulse rounded-[14px] bg-tm-panel" />)}
        </div>
      ) : plans.length === 0 ? (
        <div className="rounded-[14px] bg-tm-panel px-6 py-12 text-center text-tm-muted">플랜 정보를 불러오지 못했습니다.</div>
      ) : (
        <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(260px,1fr))" }}>
          {plans.map((plan) => (
            <PlanCard
              key={plan.id}
              plan={plan}
              isCurrent={currentCode === plan.code}
              onSubscribe={(code) => subscribeMutation.mutate(code)}
              isPending={subscribeMutation.isPending}
            />
          ))}
        </div>
      )}

      <PanelRow>
        <Panel tabs={["현재 구독"]} actions={[]} closable={false} className="flex-[1_1_360px]">
          <div className="flex flex-wrap items-center gap-3">
            <span className="grid h-[30px] w-11 place-items-center rounded-md bg-tm-raised text-tm-soft">
              <Icon name="card" size={18} />
            </span>
            <div className="flex min-w-0 flex-col gap-0.5">
              <span className="font-semibold">
                {billingLoading
                  ? "불러오는 중..."
                  : billingStatus?.registered
                    ? `${billingStatus.cardCompany ?? "카드"} 끝자리 ${billingStatus.cardLast4 ?? "—"}`
                    : "등록된 카드 없음"}
              </span>
              <span className="text-xs text-tm-muted">
                {billingStatus?.registered ? "토스페이먼츠 자동결제" : "유료 플랜 자동 갱신을 위해 등록해주세요"}
              </span>
            </div>
            <span className="ml-auto">
              {billingStatus?.registered ? (
                <Btn kind="danger" size="sm" className="h-[34px]" onClick={() => deregisterCardMutation.mutate()} disabled={deregisterCardMutation.isPending}>
                  {deregisterCardMutation.isPending ? "처리 중..." : "카드 해지"}
                </Btn>
              ) : (
                <Btn kind="ghost" size="sm" className="h-[34px]" onClick={() => registerCardMutation.mutate()} disabled={registerCardMutation.isPending || billingLoading}>
                  {registerCardMutation.isPending ? "이동 중..." : "카드 등록"}
                </Btn>
              )}
            </span>
          </div>
          <KV
            k="현재 플랜"
            mono={false}
            v={
              mySub ? (
                <>
                  {mySub.planCode}{" "}
                  <span className={cn("text-xs", STATUS_LABELS[mySub.status]?.tone)}>{STATUS_LABELS[mySub.status]?.label}</span>
                </>
              ) : (
                "구독 없음"
              )
            }
          />
          <KV
            k="다음 결제"
            mono={false}
            v={mySub?.nextBillingAt
              ? `${nextBillingText(mySub)} 01:00 · ${(mySub.nextBillingAmount ?? 0).toLocaleString("ko-KR")}원`
              : nextBillingText(mySub)}
          />
          {mySub?.noChargeReason === "NO_BILLING_KEY" && (
            <span className="text-xs text-dracula-orange">자동결제 카드가 없어 만료일에 갱신 결제가 되지 않습니다. 카드를 등록해주세요.</span>
          )}
          <KV k={mySub?.status === "CANCELLED" ? "이용 종료일" : "만료일"} v={fmtDate(mySub?.expiresAt)} />
          <div className="flex flex-wrap items-center justify-between gap-2">
            <button
              type="button"
              aria-expanded={showHistory}
              onClick={() => setShowHistory((v) => !v)}
              className="text-xs text-dracula-purple hover:text-[#d6bcfb]"
            >
              결제 내역 · 영수증 {showHistory ? "접기" : "보기"}
            </button>
            {mySub?.status === "ACTIVE" && mySub.planCode !== "FREE" && (
              <Btn kind="danger" size="sm" onClick={() => cancelMutation.mutate()} disabled={cancelMutation.isPending}>
                {cancelMutation.isPending ? "처리 중..." : "구독 해지"}
              </Btn>
            )}
          </div>
          {showHistory && (
            <>
              <Divider />
              {paymentsLoading ? (
                <p className="m-0 py-4 text-center text-tm-muted">불러오는 중...</p>
              ) : (
                <DataTable
                  columns={PAYMENT_COLUMNS}
                  rows={paymentsData ?? []}
                  rowKey={(p) => p.id}
                  minWidth={420}
                  empty="결제 내역이 없습니다."
                />
              )}
            </>
          )}
        </Panel>

        <Panel tabs={["자주 묻는 질문"]} actions={[]} closable={false} className="flex-[2_1_480px]">
          <div>
            {FAQ.map((f) => (
              <details key={f.q} className="border-b border-tm-line py-2.5">
                <summary className="cursor-pointer font-semibold">{f.q}</summary>
                <p className="mb-0 mt-2 text-13 leading-relaxed text-tm-soft">{f.a}</p>
              </details>
            ))}
          </div>
        </Panel>
      </PanelRow>

      <p className="m-0 py-2 text-center text-xs text-tm-muted">교육 목적 시뮬레이션 서비스입니다. 실제 투자 조언이 아닙니다.</p>
    </TerminalPage>
  );
}
