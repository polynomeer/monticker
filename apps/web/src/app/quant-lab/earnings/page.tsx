"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { EarningsSummaryResponse, EarningSummary, CreatorEarning, CreatorPayout, PageResponse } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import {
  AutoGrid, Btn, BtnLink, DataTable, Field, KV, Notice, Panel, PanelCol, PanelRow, Pill, SelectBox, Stat, TerminalPage, Tile, type Column, type Tone,
} from "@/components/terminal";

function won(n: number) {
  return n.toLocaleString("ko-KR") + "원";
}

const PAYOUT_STATUS: Record<string, { label: string; tone: Tone }> = {
  REQUESTED: { label: "검토 중",   tone: "orange" },
  APPROVED:  { label: "승인됨",    tone: "cyan" },
  REJECTED:  { label: "거절됨",    tone: "red" },
  PAID:      { label: "지급 완료", tone: "green" },
};

const EARNING_STATUS: Record<string, { label: string; tone: Tone }> = {
  AVAILABLE: { label: "지급 가능", tone: "green" },
  PAID_OUT:  { label: "지급됨",    tone: "muted" },
  CANCELLED: { label: "취소",      tone: "red" },
};

const BANKS = ["KB국민은행", "신한은행", "우리은행", "하나은행", "NH농협은행", "IBK기업은행", "SC제일은행", "카카오뱅크", "토스뱅크", "케이뱅크", "새마을금고", "우체국", "수협은행", "부산은행", "대구은행"];

type PayoutForm = { amount: number; bankName: string; accountNumber: string; accountHolder: string };

/** 시안의 "수익 출금 신청" 패널 — 기존 모달 폼과 같은 검증(최소 10,000원·잔액 이하·필수값)을 인라인으로. */
function PayoutPanel({ available, pending, onSubmit }: { available: number; pending: boolean; onSubmit: (f: PayoutForm, reset: () => void) => void }) {
  const empty = { amount: "", bankName: "", accountNumber: "", accountHolder: "" };
  const [form, setForm] = useState(empty);
  const disabled = available < 10000;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const amount = Number(form.amount);
    if (amount < 10000) return;
    onSubmit({ amount, bankName: form.bankName, accountNumber: form.accountNumber, accountHolder: form.accountHolder }, () => setForm(empty));
  };

  return (
    <Panel tabs={["수익 출금 신청"]} actions={[]} closable={false} className="flex-[1_1_340px] self-start">
      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <KV k="출금 가능" v={won(available)} valueClassName="text-dracula-purple" />
        <KV k="최소 출금" v="10,000원" />
        <Field
          label="출금 금액"
          unit="원"
          type="number"
          required
          min={10000}
          max={available}
          value={form.amount}
          onChange={e => setForm(f => ({ ...f, amount: e.target.value }))}
          placeholder="금액 입력"
          disabled={disabled}
        />
        <SelectBox label="은행" required value={form.bankName} onChange={e => setForm(f => ({ ...f, bankName: e.target.value }))} disabled={disabled}>
          <option value="">은행 선택</option>
          {BANKS.map(b => <option key={b} value={b}>{b}</option>)}
        </SelectBox>
        <Field
          label="계좌번호"
          required
          inputMode="numeric"
          pattern="[0-9]+"
          title="숫자만 입력하세요"
          value={form.accountNumber}
          onChange={e => setForm(f => ({ ...f, accountNumber: e.target.value }))}
          placeholder="숫자만 입력"
          disabled={disabled}
        />
        <Field
          label="예금주"
          mono={false}
          required
          value={form.accountHolder}
          onChange={e => setForm(f => ({ ...f, accountHolder: e.target.value }))}
          placeholder="본인 명의"
          disabled={disabled}
        />
        <Notice tone="info">신청 후 운영 검토를 거쳐 지급됩니다. 지급 일정과 원천징수 여부는 정산 정책을 따릅니다.</Notice>
        <Btn type="submit" full size="lg" disabled={disabled || pending}>
          {pending ? "신청 중..." : "출금 신청"}
        </Btn>
        {disabled && <span className="text-center text-2xs text-tm-muted">출금 가능 금액이 10,000원 이상일 때 신청할 수 있습니다.</span>}
      </form>
    </Panel>
  );
}

export default function EarningsPage() {
  const [tab, setTab] = useState<"overview" | "earnings" | "payouts">("overview");
  const [earningPage, setEarningPage] = useState(0);
  const [payoutPage, setPayoutPage] = useState(0);
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: summary } = useQuery<EarningsSummaryResponse>({
    queryKey: ["earnings", "summary"],
    queryFn: () => authFetch("/api/settlement/strategy/earnings/summary").then(r => r.json()),
  });
  const balance = summary?.availableBalance ?? 0;
  const byStrategy = summary?.byStrategy ?? [];
  const totalNet = byStrategy.reduce((a, r) => a + r.totalNet, 0);

  const { data: earningsData } = useQuery<PageResponse<CreatorEarning>>({
    queryKey: ["earnings", "list", earningPage],
    queryFn: () =>
      authFetch(`/api/settlement/strategy/earnings?page=${earningPage}&size=20`).then(r => r.json()),
    enabled: tab === "earnings",
  });

  const { data: payoutsData } = useQuery<PageResponse<CreatorPayout>>({
    queryKey: ["earnings", "payouts", payoutPage],
    queryFn: () =>
      authFetch(`/api/settlement/strategy/payouts?page=${payoutPage}&size=20`).then(r => r.json()),
    enabled: tab === "payouts",
  });

  const payoutMutation = useMutation({
    mutationFn: (body: PayoutForm) =>
      authFetch("/api/settlement/strategy/payout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(async r => {
        if (!r.ok) throw new Error(await r.text());
        return r.json();
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["earnings"] });
      toast({ type: "success", title: "출금 신청 완료", message: "검토 후 지급됩니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "출금 신청 실패", message: e.message }),
  });

  const strategyCols: Column<EarningSummary>[] = [
    { key: "s", header: "전략", cell: r => <span className="font-semibold">전략 #{r.strategyId}</span> },
    { key: "subs", header: "구독자", align: "right", cell: () => <span className="num text-tm-muted">—</span> },
    { key: "month", header: "이번 달 순수익", align: "right", cell: () => <span className="num text-tm-muted">—</span> },
    { key: "total", header: "누적 순수익", align: "right", cell: r => <span className="num text-up">{won(r.totalNet)}</span> },
    { key: "churn", header: "이탈률", align: "right", cell: () => <span className="num text-tm-muted">—</span> },
    { key: "st", header: "상태", cell: () => <span className="text-tm-muted">—</span> },
  ];
  const earningCols: Column<CreatorEarning>[] = [
    { key: "s", header: "전략", cell: e => <span className="font-medium">#{e.strategyId}</span> },
    { key: "d", header: "날짜", cell: e => <span className="num text-tm-muted">{new Date(e.earnedAt).toLocaleDateString("ko-KR")}</span> },
    { key: "g", header: "총액", align: "right", cell: e => <span className="num">{won(e.grossAmount)}</span> },
    { key: "f", header: "수수료", align: "right", cell: e => <span className="num text-tm-muted">-{won(e.platformFee)}</span> },
    { key: "n", header: "순수익", align: "right", cell: e => <span className="num font-semibold text-up">+{won(e.netAmount)}</span> },
    { key: "st", header: "상태", cell: e => { const m = EARNING_STATUS[e.status]; return <Pill tone={m?.tone ?? "muted"}>{m?.label ?? e.status}</Pill>; } },
  ];
  const payoutCols: Column<CreatorPayout>[] = [
    { key: "a", header: "금액", align: "right", cell: p => <span className="num font-semibold">{won(p.amount)}</span> },
    { key: "acc", header: "계좌", cell: p => <span className="text-tm-soft">{p.bankName} {(p.accountNumber ?? "").slice(-4).padStart((p.accountNumber?.length ?? 0), "•")} ({p.accountHolder})</span> },
    { key: "r", header: "신청일", cell: p => <span className="num text-tm-muted">{new Date(p.requestedAt).toLocaleDateString("ko-KR")}</span> },
    { key: "p", header: "처리일", cell: p => <span className="num text-tm-muted">{p.processedAt ? new Date(p.processedAt).toLocaleDateString("ko-KR") : "—"}</span> },
    { key: "st", header: "상태", cell: p => { const m = PAYOUT_STATUS[p.status]; return <Pill tone={m?.tone ?? "muted"}>{m?.label ?? p.status}</Pill>; } },
  ];

  const pager = (page: number, total: number, set: (f: (p: number) => number) => void) =>
    total > 1 && (
      <div className="flex justify-center gap-2 pt-2">
        {page > 0 && <Btn kind="soft" size="sm" onClick={() => set(p => p - 1)}>이전</Btn>}
        {page < total - 1 && <Btn kind="soft" size="sm" onClick={() => set(p => p + 1)}>다음</Btn>}
      </div>
    );

  return (
    <TerminalPage
      title="제작자 수익 대시보드"
      crumb="전략 마켓"
      stats={[
        { label: "판매 전략", value: `${byStrategy.length}개` },
        { label: "구독자", value: "—", tone: "text-tm-muted" },
        { label: "평균 별점", value: "—", tone: "text-tm-muted" },
        { label: "다음 정산", value: "—", tone: "text-tm-muted" },
      ]}
    >
      <PanelRow>
        <PanelCol className="flex-[999_1_620px]">
          <AutoGrid min={180} gap={8}>
            <Tile><Stat big label="누적 순수익" value={won(totalNet)} sub="플랫폼 수수료 차감 후" /></Tile>
            <Tile><Stat big label="이번 달" value="—" valueClassName="text-tm-muted" sub="월별 집계 준비 중" /></Tile>
            <Tile><Stat big label="활성 구독자" value="—" valueClassName="text-tm-muted" sub="집계 준비 중" /></Tile>
            <Tile><Stat big label="출금 가능" value={won(balance)} valueClassName="text-dracula-purple" sub="최소 10,000원" /></Tile>
          </AutoGrid>

          <Panel tabs={["월별 수익"]} actions={[]} closable={false} preview>
            <div className="grid h-[180px] place-items-center rounded-lg border border-dashed border-tm-line2 text-center text-13 text-tm-muted">
              <div>월별 순수익 차트는 월 단위 집계 API가 준비되면 표시됩니다.<br />개별 수익 내역은 아래 &lsquo;수익 내역&rsquo; 탭에서 볼 수 있습니다.</div>
            </div>
          </Panel>

          <Panel
            tabs={[{ key: "overview", label: "전략별" }, { key: "earnings", label: "수익 내역" }, { key: "payouts", label: "출금 내역" }]}
            active={tab}
            onTabChange={k => setTab(k as typeof tab)}
            actions={[]}
            closable={false}
            bodyClassName="px-1.5 pb-1.5 pt-1"
          >
            {tab === "overview" && (
              byStrategy.length === 0 ? (
                <div className="flex flex-col items-center gap-3 py-12 text-center">
                  <p className="m-0 text-13 text-tm-muted">아직 수익이 없습니다. 전략을 공유하고 구독자를 모아보세요.</p>
                  <BtnLink href="/quant-lab/builder" size="sm">룰셋 만들기 →</BtnLink>
                </div>
              ) : (
                <DataTable columns={strategyCols} rows={byStrategy} rowKey={r => r.strategyId} minWidth={640} />
              )
            )}
            {tab === "earnings" && (
              <>
                <DataTable columns={earningCols} rows={earningsData?.content ?? []} rowKey={e => e.id} minWidth={640} empty="수익 내역이 없습니다." />
                {pager(earningPage, earningsData?.totalPages ?? 1, setEarningPage)}
              </>
            )}
            {tab === "payouts" && (
              <>
                <DataTable columns={payoutCols} rows={payoutsData?.content ?? []} rowKey={p => p.id} minWidth={640} empty="출금 내역이 없습니다." />
                {pager(payoutPage, payoutsData?.totalPages ?? 1, setPayoutPage)}
              </>
            )}
          </Panel>
          <p className="m-0 text-2xs text-tm-muted">수익은 구독자 결제 금액의 70%입니다.</p>
        </PanelCol>

        <PayoutPanel
          available={balance}
          pending={payoutMutation.isPending}
          onSubmit={(form, reset) => payoutMutation.mutate(form, { onSuccess: reset })}
        />
      </PanelRow>
    </TerminalPage>
  );
}
