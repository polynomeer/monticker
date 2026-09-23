"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { CreateWatchRuleRequest, UpdateWatchRuleRequest } from "@monticker/types";
import {
  createWatchRule,
  deleteWatchRule,
  getWatchRuleExecutions,
  getWatchRules,
  updateWatchRule,
} from "@/services/watchrule";

const RULES_KEY = ["watch-rules"] as const;
const EXECUTIONS_KEY = ["watch-rules", "executions"] as const;

export function useWatchRules(enabled: boolean) {
  return useQuery({ queryKey: RULES_KEY, queryFn: getWatchRules, enabled });
}

/**
 * 발동은 이벤트가 감지될 때만 일어난다 — 사용자가 화면을 보는 동안 조용히 늘어나므로 주기적으로 다시 읽는다.
 * 틱처럼 잦지 않아 30초면 충분하다.
 */
export function useWatchRuleExecutions(enabled: boolean) {
  return useQuery({
    queryKey: EXECUTIONS_KEY,
    queryFn: () => getWatchRuleExecutions(50),
    enabled,
    refetchInterval: enabled ? 30_000 : false,
  });
}

export function useCreateWatchRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateWatchRuleRequest) => createWatchRule(req),
    onSuccess: () => { qc.invalidateQueries({ queryKey: RULES_KEY }); },
  });
}

export function useUpdateWatchRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ ruleId, req }: { ruleId: number; req: UpdateWatchRuleRequest }) => updateWatchRule(ruleId, req),
    onSuccess: () => { qc.invalidateQueries({ queryKey: RULES_KEY }); },
  });
}

export function useDeleteWatchRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ruleId: number) => deleteWatchRule(ruleId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: RULES_KEY }); },
  });
}
