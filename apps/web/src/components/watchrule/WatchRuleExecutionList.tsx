"use client";

import { ClockCounterClockwise } from "@phosphor-icons/react";
import type { WatchRuleExecutionResponse, WatchRuleExecutionStatus, WatchRuleResponse } from "@monticker/types";
import EmptyState from "@/components/common/EmptyState";
import { Badge } from "@/components/ui/Badge";
import { EVENT_LABEL } from "./WatchRuleRow";

const STATUS_LABEL: Record<WatchRuleExecutionStatus, string> = {
  EXECUTED: "체결",
  REJECTED: "거부",
  SKIPPED: "건너뜀",
};

const STATUS_VARIANT: Record<WatchRuleExecutionStatus, "up" | "down" | "neutral"> = {
  EXECUTED: "up",
  REJECTED: "down",
  SKIPPED: "neutral",
};

interface Props {
  executions: WatchRuleExecutionResponse[];
  /** 규칙 id → 표시 이름. 규칙이 삭제됐으면 이력만 남으므로 없을 수 있다. */
  ruleLabels: Map<number, { stockLabel: string; rule: WatchRuleResponse }>;
}

export function WatchRuleExecutionList({ executions, ruleLabels }: Props) {
  if (!executions.length) {
    return (
      <EmptyState
        icon={ClockCounterClockwise}
        title="아직 발동한 규칙이 없습니다"
        description="이벤트가 감지되면 여기에 체결·거부·건너뜀이 이유와 함께 기록됩니다."
      />
    );
  }

  return (
    <ul className="divide-y divide-gray-100 dark:divide-dracula-line/40">
      {executions.map((e) => {
        const meta = ruleLabels.get(e.watchRuleId);
        return (
          <li key={e.id} className="flex items-start justify-between gap-3 px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-sm font-medium text-gray-900 dark:text-dracula-fg">
                  {meta?.stockLabel ?? `규칙 #${e.watchRuleId}`}
                </span>
                {meta && (
                  <span className="text-xs text-gray-500 dark:text-dracula-comment">
                    {EVENT_LABEL[meta.rule.eventType]}
                  </span>
                )}
              </div>
              {e.status === "EXECUTED" && e.quantity != null && (
                <p className="mt-0.5 text-xs text-gray-600 dark:text-dracula-fg">
                  {e.quantity}주
                  {e.fillPrice != null && ` @ ${e.fillPrice.toLocaleString("ko-KR")}원`}
                </p>
              )}
              {/* 거부·건너뜀은 이유가 핵심이다 — "왜 안 샀지"에 답하는 자리다. */}
              {e.reason && (
                <p className="mt-0.5 text-xs text-gray-500 dark:text-dracula-comment">{e.reason}</p>
              )}
              <p className="mt-0.5 text-xs text-gray-400 dark:text-dracula-line">
                {new Date(e.createdAt).toLocaleString("ko-KR")}
              </p>
            </div>
            <Badge variant={STATUS_VARIANT[e.status]} className="mt-0.5 shrink-0">
              {STATUS_LABEL[e.status]}
            </Badge>
          </li>
        );
      })}
    </ul>
  );
}
