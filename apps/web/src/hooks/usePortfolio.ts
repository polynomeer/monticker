"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { AddPortfolioTransactionRequest, CreatePortfolioAccountRequest, HoldingsScope, ImportOptions, PortfolioConsent } from "@monticker/types";
import {
  addTransaction,
  agreePortfolioConsent,
  commitImport,
  createAccount,
  deleteAccount,
  deleteAllPortfolioData,
  deleteTransaction,
  getAccounts,
  getAnalysis,
  getHoldingEvents,
  getHoldings,
  getImports,
  getTransactions,
  previewImport,
  revertImport,
} from "@/services/portfolio";
import { consentKey } from "./useConsents";

/** 통합 포트폴리오(ADR-106) — 계좌·거래가 바뀌면 보유·소식·계좌 목록을 함께 다시 읽는다 */
const root = ["assets"] as const;
const keys = {
  holdings: (scope: HoldingsScope) => [...root, "holdings", scope] as const,
  events: [...root, "events"] as const,
  analysis: [...root, "analysis"] as const,
  accounts: [...root, "accounts"] as const,
  transactions: (accountId: number) => [...root, "transactions", accountId] as const,
  imports: (accountId: number) => [...root, "imports", accountId] as const,
};

function useInvalidateAll() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: root });
}

export function useHoldings(scope: HoldingsScope, enabled: boolean) {
  // 시세(1분봉 최신 종가)가 바뀐다 — 모의 포트폴리오보다 느슨하게
  return useQuery({ queryKey: keys.holdings(scope), queryFn: () => getHoldings(scope), enabled, refetchInterval: enabled ? 30_000 : false });
}

/** 분석은 보유와 같은 시세를 쓰므로 같은 주기로 */
export function useAnalysis(enabled: boolean) {
  return useQuery({ queryKey: keys.analysis, queryFn: getAnalysis, enabled, refetchInterval: enabled ? 30_000 : false });
}

export function useHoldingEvents(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: keys.events,
    queryFn: ({ pageParam }) => getHoldingEvents(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled,
  });
}

export function useAccounts(enabled: boolean) {
  return useQuery({ queryKey: keys.accounts, queryFn: getAccounts, enabled });
}

export function useTransactions(accountId: number | null) {
  return useQuery({
    queryKey: keys.transactions(accountId ?? -1),
    queryFn: () => getTransactions(accountId!, 0, 200),
    enabled: accountId != null,
  });
}

export function useImports(accountId: number | null, enabled: boolean) {
  return useQuery({ queryKey: keys.imports(accountId ?? -1), queryFn: () => getImports(accountId!), enabled: enabled && accountId != null });
}

export function useCreateAccount() {
  const qc = useQueryClient();
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: (req: CreatePortfolioAccountRequest) => createAccount(req),
    onSuccess: () => {
      invalidate();
      // 처음 계좌면 동의가 함께 기록됐다
      qc.invalidateQueries({ queryKey: consentKey });
    },
  });
}

export function useAgreePortfolioConsent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (consents: PortfolioConsent[]) => agreePortfolioConsent(consents),
    onSuccess: () => qc.invalidateQueries({ queryKey: consentKey }),
  });
}

export function useDeleteAccount() {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (accountId: number) => deleteAccount(accountId), onSuccess: invalidate });
}

export function useDeleteAllPortfolioData() {
  const qc = useQueryClient();
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: deleteAllPortfolioData,
    onSuccess: () => {
      invalidate();
      qc.invalidateQueries({ queryKey: consentKey });
    },
  });
}

export function useAddTransaction(accountId: number) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (req: AddPortfolioTransactionRequest) => addTransaction(accountId, req), onSuccess: invalidate });
}

export function useDeleteTransaction(accountId: number) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (transactionId: number) => deleteTransaction(accountId, transactionId), onSuccess: invalidate });
}

/** 미리보기는 저장하지 않으므로 다른 쿼리를 건드리지 않는다 */
export function usePreviewImport(accountId: number) {
  return useMutation({ mutationFn: ({ file, options }: { file: File; options: ImportOptions }) => previewImport(accountId, file, options) });
}

export function useCommitImport(accountId: number) {
  const invalidate = useInvalidateAll();
  return useMutation({
    mutationFn: ({ file, options, skipErrors, sha256 }: { file: File; options: ImportOptions; skipErrors: boolean; sha256: string }) =>
      commitImport(accountId, file, options, skipErrors, sha256),
    onSuccess: invalidate,
  });
}

export function useRevertImport(accountId: number) {
  const invalidate = useInvalidateAll();
  return useMutation({ mutationFn: (batchId: number) => revertImport(accountId, batchId), onSuccess: invalidate });
}
