"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ConnectBrokerageRequest, SubmitBrokerageOrderRequest } from "@monticker/types";
import {
  cancelBrokerageOrder,
  connectBrokerage,
  getBrokerageAccount,
  getBrokerageBalance,
  getBrokerageOrders,
  getBrokerageSettlements,
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
