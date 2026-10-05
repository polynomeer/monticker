"use client";

import { useRuleSetSignalsWs, type RuleSetSignalEvent } from "@/hooks/useRuleSetSignalsWs";

/** ADR-024 — 룰셋 하나의 포워드 테스트 신호를 실시간 구독한다. 여러 개는 useRuleSetSignalsWs를 직접 쓴다. */
export function useForwardTestSignalsWs(ruleSetId: string | undefined, onSignal: (event: RuleSetSignalEvent) => void) {
  return useRuleSetSignalsWs(ruleSetId ? [ruleSetId] : [], (_, event) => onSignal(event));
}
