"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
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
  getBrokerageAccount,
  getBrokerageBalance,
  getBrokerageOrders,
  getBrokerageSettlements,
  getConditionalOrders,
  getRebalanceTarget,
  previewRebalance,
  saveRebalanceTarget,
  submitBrokerageOrder,
} from "@/services/brokerage";

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

export function useSubmitBrokerageOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: SubmitBrokerageOrderRequest) => submitBrokerageOrder(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["brokerage", "balance"] });
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
