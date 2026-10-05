"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export interface ReconciliationDay {
  asOfDate: string;
  accountCash: number;
  reservedCash: number;
  ledgerSum: number;
  /** (잔고 + 예약금) − (초기 지급 + 원장 합). 0이어야 한다 */
  drift: number;
  mismatch: boolean;
  checkedAt: string;
}

export interface ReconciliationSummary {
  mismatchCount: number;
  windowDays: number;
  latest: ReconciliationDay | null;
  mismatches: ReconciliationDay[];
}

/** ADR-043 일일 원장 대사 결과(스냅샷) — "잔액 불일치 N건". 하루 한 번 바뀌므로 자주 읽지 않는다. */
export function useReconciliation(enabled = true) {
  return useQuery<ReconciliationSummary>({
    queryKey: ["wallet", "reconciliation"],
    queryFn: async () => {
      const r = await authFetch("/api/wallet/reconciliation");
      if (!r.ok) throw new Error("대사 결과 조회 실패");
      return r.json();
    },
    staleTime: 5 * 60_000,
    enabled,
  });
}

/** 상단 스탯·배지용 문구 */
export function reconciliationLabel(s: ReconciliationSummary | undefined): { value: string; tone: string; title?: string } {
  if (!s) return { value: "—", tone: "text-tm-muted" };
  if (!s.latest) return { value: "대사 전", tone: "text-tm-muted", title: "아직 일일 대사가 실행되지 않았습니다(거래가 있던 날 밤에 실행)" };
  if (s.mismatchCount === 0) {
    return { value: "0건", tone: "text-dracula-green", title: `최근 ${s.windowDays}일 대사 일치 · 마지막 ${s.latest.asOfDate}` };
  }
  return { value: `${s.mismatchCount}건`, tone: "text-[#ff8a8a]", title: `최근 ${s.windowDays}일 중 불일치 ${s.mismatchCount}일 — 자동 교정하지 않고 조사합니다` };
}
