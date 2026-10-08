"use client";

import type { WatchRuleExecutionResponse, WatchRuleExecutionStatus, WatchRuleResponse } from "@monticker/types";
import { Icon, Pill, type Tone } from "@/components/terminal";
import { EVENT_LABEL } from "./WatchRuleRow";

const STATUS_LABEL: Record<WatchRuleExecutionStatus, string> = {
  EXECUTED: "체결",
  PLACED: "지정가 접수",
  FILLED: "지정가 체결",
  CANCELLED: "지정가 취소",
  REJECTED: "거부",
  SKIPPED: "건너뜀",
};

const STATUS_TONE: Record<WatchRuleExecutionStatus, Tone> = {
  EXECUTED: "green",
  PLACED: "cyan",
  FILLED: "green",
  CANCELLED: "orange",
  REJECTED: "red",
  SKIPPED: "muted",
};

interface Props {
  executions: WatchRuleExecutionResponse[];
  /** 규칙 id → 표시 이름. 규칙이 삭제됐으면 이력만 남으므로 없을 수 있다. */
  ruleLabels: Map<number, { stockLabel: string; rule: WatchRuleResponse }>;
  /** ADR-095 — 발동 종목 라벨(그룹 규칙은 발동마다 종목이 다르다) */
  stockLabel?: (stockId: number) => string;
}

export function WatchRuleExecutionList({ executions, ruleLabels, stockLabel }: Props) {
  if (!executions.length) {
    return (
      <div className="flex flex-col items-center gap-2 py-10 text-center">
        <Icon name="clock" size={22} className="text-tm-muted" />
        <p className="m-0 text-sm font-semibold">아직 발동한 규칙이 없습니다</p>
        <p className="m-0 text-xs text-tm-muted">이벤트가 감지되면 여기에 체결·지정가 접수와 그 결과·거부·건너뜀이 이유와 함께 기록됩니다.</p>
      </div>
    );
  }

  return (
    <ul className="m-0 flex list-none flex-col p-0">
      {executions.map((e) => {
        const meta = ruleLabels.get(e.watchRuleId);
        return (
          <li key={e.id} className="flex items-start justify-between gap-3 border-b border-tm-line px-1 py-3">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-13 font-semibold">{meta?.stockLabel ?? `규칙 #${e.watchRuleId}`}</span>
                {meta && <span className="text-xs text-tm-muted">{EVENT_LABEL[meta.rule.eventType]}</span>}
                {meta?.rule.targetType === "GROUP" && e.stockId != null && stockLabel && (
                  <span className="text-xs text-tm-soft">→ {stockLabel(e.stockId)}</span>
                )}
              </div>
              {e.status === "PLACED" && e.quantity != null && (
                <p className="num m-0 text-xs text-tm-soft">
                  {e.quantity}주{e.limitPrice != null && ` 지정가 ${e.limitPrice.toLocaleString("ko-KR")}원`} · 미체결
                </p>
              )}
              {/* ADR-098 — 접수했던 지정가의 이후 결과. 체결가·결과 시각을 함께 보인다. */}
              {e.status === "FILLED" && e.quantity != null && (
                <p className="num m-0 text-xs text-tm-soft">
                  {e.quantity}주
                  {e.limitPrice != null && ` 지정가 ${e.limitPrice.toLocaleString("ko-KR")}원`}
                  {e.fillPrice != null && ` → 체결 ${e.fillPrice.toLocaleString("ko-KR")}원`}
                  {e.resolvedAt && ` · ${new Date(e.resolvedAt).toLocaleString("ko-KR")}`}
                </p>
              )}
              {e.status === "CANCELLED" && (
                <p className="num m-0 text-xs text-tm-soft">
                  {e.quantity != null && `${e.quantity}주`}
                  {e.limitPrice != null && ` 지정가 ${e.limitPrice.toLocaleString("ko-KR")}원`} · 미체결 취소
                  {e.resolvedAt && ` · ${new Date(e.resolvedAt).toLocaleString("ko-KR")}`}
                </p>
              )}
              {e.status === "EXECUTED" && e.quantity != null && (
                <p className="num m-0 text-xs text-tm-soft">
                  {e.quantity}주
                  {e.fillPrice != null && ` @ ${e.fillPrice.toLocaleString("ko-KR")}원`}
                </p>
              )}
              {/* 거부·건너뜀은 이유가 핵심이다 — "왜 안 샀지"에 답하는 자리다. */}
              {e.reason && e.status !== "PLACED" && e.status !== "FILLED" && <p className="m-0 text-xs text-tm-muted">{e.reason}</p>}
              <p className="num m-0 text-2xs text-tm-muted">{new Date(e.createdAt).toLocaleString("ko-KR")}</p>
            </div>
            <Pill tone={STATUS_TONE[e.status]} className="mt-0.5 shrink-0">{STATUS_LABEL[e.status]}</Pill>
          </li>
        );
      })}
    </ul>
  );
}
