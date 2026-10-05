"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  BrokerageOrderStatus,
  ConnectBrokerageRequest,
  CreateConditionalOrderRequest,
  CreateOcoOrderRequest,
  SaveRebalanceTargetRequest,
  SubmitBrokerageOrderRequest,
} from "@monticker/types";
import {
  cancelBrokerageOrder,
  cancelConditionalOrder,
  connectBrokerage,
  createConditionalOrder,
  createOcoOrder,
  executeRebalance,
  getActiveBrokerageOrdersForSymbol,
  getBrokerageAccount,
  getBrokerageBalance,
  getBrokerageOrders,
  getBrokerageSettlements,
  getConditionalOrders,
  getRebalanceTarget,
  previewRebalance,
  saveRebalanceTarget,
  getTradingStatus,
  submitBrokerageOrder,
  syncBrokerageOrder, disconnectBrokerage } from "@/services/brokerage";

/** ADR-056 — 증권사에서의 상태를 아직 모른다. 같은 종목·방향 주문이 서버에서 막히고, 목록은 자동 갱신한다. */
export const isUnresolvedOrderStatus = (s: BrokerageOrderStatus) => s === "PENDING_SUBMIT" || s === "UNKNOWN";

export function useBrokerageAccount() {
  return useQuery({
    queryKey: ["brokerage", "account"],
    queryFn: getBrokerageAccount,
  });
}

export function useBrokerageBalance(enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "balance"],
    queryFn: getBrokerageBalance,
    enabled,
    refetchInterval: enabled ? 10_000 : false,
  });
}

export function useBrokerageOrders(page: number, enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "orders", page],
    queryFn: () => getBrokerageOrders(page),
    enabled,
    // ADR-056 — 결과 확인 중인 주문이 있는 동안만 15초마다 다시 읽는다(서버 대조 잡 주기 30초).
    refetchInterval: (query) =>
      query.state.data?.content.some((o) => isUnresolvedOrderStatus(o.status)) ? 15_000 : false,
  });
}

/** 종목 상세 차트의 주문선용 — 계좌 연동돼 있을 때만 활성화 */
export function useActiveBrokerageOrdersForSymbol(symbol: string, enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "orders", "active", symbol],
    queryFn:  () => getActiveBrokerageOrdersForSymbol(symbol),
    enabled,
    refetchInterval: enabled ? 10_000 : false,
  });
}

export function useBrokerageSettlements(page: number, enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "settlements", page],
    queryFn: () => getBrokerageSettlements(page),
    enabled,
  });
}

export function useConnectBrokerage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: ConnectBrokerageRequest) => connectBrokerage(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage"] });
    },
  });
}

/** ADR-067 — 연동 해지. 성공하면 계좌·잔고·조건부 주문 캐시를 모두 버린다. */
export function useDisconnectBrokerage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => disconnectBrokerage(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage"] });
    },
  });
}

export function useSubmitBrokerageOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: SubmitBrokerageOrderRequest) => submitBrokerageOrder(req),
    // ADR-057 — 423(킬 스위치)일 수 있다. 배너가 다음 포커스까지 기다리지 않도록 상태를 다시 읽는다.
    onError: () => qc.invalidateQueries({ queryKey: ["brokerage", "trading-status"] }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "balance"] });
      qc.invalidateQueries({ queryKey: ["brokerage", "orders"] });
    },
  });
}

/** ADR-057 — 실주문 킬 스위치 상태. 켜져 있는 동안만 30초마다 다시 읽어 해제를 빨리 반영한다. */
export function useTradingStatus(enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "trading-status"],
    queryFn: getTradingStatus,
    enabled,
    refetchInterval: (query) => (query.state.data?.halted ? 30_000 : false),
  });
}

/** ADR-056 — 결과 불명 주문은 서버가 즉시 증권사와 한 번 대조한다. */
export function useSyncBrokerageOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => syncBrokerageOrder(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "orders"] });
    },
  });
}

export function useCancelBrokerageOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => cancelBrokerageOrder(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "orders"] });
    },
  });
}

// ── 조건부 주문 (ADR-032) ────────────────────────────────────────────────────

export function useConditionalOrders(page: number, enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "conditional-orders", page],
    queryFn: () => getConditionalOrders(page),
    enabled,
    // 발동 대기 중인 주문 상태가 실시간으로 바뀔 수 있으므로 짧은 주기로 갱신한다.
    refetchInterval: enabled ? 5_000 : false,
  });
}

export function useCreateConditionalOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateConditionalOrderRequest) => createConditionalOrder(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["brokerage", "conditional-orders"] }),
  });
}

export function useCreateOcoOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateOcoOrderRequest) => createOcoOrder(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["brokerage", "conditional-orders"] }),
  });
}

export function useCancelConditionalOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => cancelConditionalOrder(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["brokerage", "conditional-orders"] }),
  });
}

// ── 리밸런싱 (ADR-034) ───────────────────────────────────────────────────────

export function useRebalanceTarget(enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "rebalance-target"],
    queryFn: getRebalanceTarget,
    enabled,
  });
}

export function useSaveRebalanceTarget() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: SaveRebalanceTargetRequest) => saveRebalanceTarget(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "rebalance-target"] });
      qc.invalidateQueries({ queryKey: ["brokerage", "rebalance-preview"] });
    },
  });
}

export function useRebalancePreview(enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "rebalance-preview"],
    queryFn: previewRebalance,
    enabled,
  });
}

export function useExecuteRebalance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => executeRebalance(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "balance"] });
      qc.invalidateQueries({ queryKey: ["brokerage", "rebalance-preview"] });
    },
  });
}
