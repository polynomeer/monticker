"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getBrokerageAccount, getBrokerageBalance } from "@/services/brokerage";
import type { LedgerEvent } from "@/hooks/useWalletLedger";

/** `GET /api/wallet` — 모의계좌의 돈 위치 요약 */
export interface PaperWallet {
  availableCash: number;
  reservedCash: number;
  holdingsValue: number;
  settlementPending: number;
  totalAssets: number;
  recentLedger: LedgerEvent[];
}

export const PAPER_WALLET_KEY = ["wallet"] as const;

async function fetchPaperWallet(): Promise<PaperWallet> {
  const res = await authFetch("/api/wallet");
  if (!res.ok) throw new Error("지갑 정보 조회 실패");
  return res.json();
}

/**
 * 모의계좌 지갑 — 셸 상단 계좌 칩과 /wallet이 같은 쿼리를 공유한다.
 * 칩은 모든 화면에 있으므로 주기 조회를 하지 않고 30초 동안 신선하다고 본다. /wallet은 refetchInterval을 따로 준다.
 */
export function usePaperWallet(enabled: boolean, opts: { refetchInterval?: number } = {}) {
  return useQuery<PaperWallet>({
    queryKey: PAPER_WALLET_KEY,
    queryFn: fetchPaperWallet,
    enabled,
    staleTime: 30_000,
    refetchInterval: opts.refetchInterval,
  });
}

/** 칩 표시용 금액 — 숫자를 모르면(로딩·오류·비정상 값) 지어내지 않고 `—` */
export function chipAmount(q: { data?: number | null; isError?: boolean }): string {
  if (q.isError || q.data == null || !Number.isFinite(q.data)) return "—";
  return `${Math.round(q.data).toLocaleString("ko-KR")}원`;
}

export interface AccountChipSummary {
  /** 표시할 금액 문자열. undefined면 칩에 금액 자리를 그리지 않는다(로그아웃, 실계좌 미연동) */
  amount?: string;
  /** 금액이 무엇인지 — 스크린리더·툴팁용 */
  amountLabel?: string;
}

/**
 * 상단 계좌 칩 요약 — 모의는 총자산, 실전은 연동된 계좌가 있을 때만 가용 현금.
 * 실계좌 잔고는 증권사 API를 부르므로 실전 화면(kind="live")에서만 조회한다.
 */
export function useAccountChipSummary(kind: "paper" | "live", isLoggedIn: boolean): AccountChipSummary {
  const live = kind === "live";
  const paper = usePaperWallet(isLoggedIn && !live);
  const account = useQuery({
    queryKey: ["brokerage", "account"],
    queryFn: getBrokerageAccount,
    enabled: isLoggedIn && live,
    staleTime: 60_000,
  });
  const connected = !!account.data?.isActive;
  const balance = useQuery({
    queryKey: ["brokerage", "balance"],
    queryFn: getBrokerageBalance,
    enabled: isLoggedIn && live && connected,
    staleTime: 30_000,
  });

  if (!isLoggedIn) return {};
  if (!live) return { amount: chipAmount({ data: paper.data?.totalAssets, isError: paper.isError }), amountLabel: "모의 총자산" };
  // 연동 여부를 아직 모르면 금액 자리는 `—`, 미연동이면 금액을 그리지 않는다
  if (account.isPending || account.isError) return { amount: "—", amountLabel: "실계좌 가용 현금" };
  if (!connected) return {};
  return { amount: chipAmount({ data: balance.data?.cash, isError: balance.isError }), amountLabel: "실계좌 가용 현금" };
}
