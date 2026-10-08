"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { CreateWatchRuleRequest, UpdateWatchRuleRequest, WatchRuleResponse } from "@monticker/types";
import { applyOptimisticPatch, isOptimisticSafe } from "@/components/watchrule/watchRulePatch";
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

/**
 * 규칙 수정(PATCH). 이름·쿨다운·하루 한도·중요도·같은 기준 안의 값만 바꾸는 요청은 목록에 먼저 반영하고(실패하면 되돌린다),
 * 대상·주문 유형·수량 기준·켜기를 바꾸는 요청은 서버 응답을 기다린다 — 서버가 소유·존재를 다시 확인하고 거부할 수 있다(ADR-098).
 */
export function useUpdateWatchRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ ruleId, req }: { ruleId: number; req: UpdateWatchRuleRequest }) => updateWatchRule(ruleId, req),
    onMutate: async ({ ruleId, req }) => {
      if (!isOptimisticSafe(req)) return { previous: undefined };
      await qc.cancelQueries({ queryKey: RULES_KEY, exact: true });
      const previous = qc.getQueryData<WatchRuleResponse[]>(RULES_KEY);
      if (previous) {
        qc.setQueryData<WatchRuleResponse[]>(RULES_KEY, previous.map((r) => (r.id === ruleId ? applyOptimisticPatch(r, req) : r)));
      }
      return { previous };
    },
    onError: (_e, _vars, ctx) => {
      if (ctx?.previous) qc.setQueryData(RULES_KEY, ctx.previous);
    },
    onSettled: () => { qc.invalidateQueries({ queryKey: RULES_KEY }); },
  });
}

export function useDeleteWatchRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ruleId: number) => deleteWatchRule(ruleId),
    onSuccess: () => { qc.invalidateQueries({ queryKey: RULES_KEY }); },
  });
}
