import type { UpdateWatchRuleRequest, WatchRuleResponse } from "@monticker/types";
import type { WatchRuleFormValue } from "./WatchRuleForm";

/**
 * ADR-098 — 수정 폼의 값(생성과 같은 모양)을 규칙과 비교해 PATCH 본문을 만든다. **바뀐 값만** 담는다.
 *
 * 서버 규칙(WatchRuleService.update)과 맞춘다:
 * - 기준(대상·주문 유형·수량 기준)이 바뀌면 기준 키와 새 기준의 값을 함께 보낸다(서버는 바뀐 기준의 옛 값을 잇지 않는다).
 * - 기준이 그대로면 그 기준의 값이 바뀌었을 때만 값만 보낸다.
 * - 이름을 지우면 "", 하루 한도를 없애면 0(서버의 "제한 해제").
 * - 감지 조건·매수/매도·전략은 PATCH가 받지 않으므로 담지 않는다.
 */
export function buildWatchRulePatch(
  rule: WatchRuleResponse,
  value: WatchRuleFormValue,
  opts: { reactivate?: boolean } = {},
): UpdateWatchRuleRequest {
  const patch: UpdateWatchRuleRequest = {};

  const name = value.name ?? "";
  if (name !== (rule.name ?? "")) patch.name = name;
  if (rule.eventType !== "QUANT_SIGNAL" && value.minImportanceScore !== rule.minImportanceScore) {
    patch.minImportanceScore = value.minImportanceScore;
  }
  if (value.cooldownSec !== rule.cooldownSec) patch.cooldownSec = value.cooldownSec;
  const limit = value.dailyLimit ?? null;
  if (limit !== (rule.dailyLimit ?? null)) patch.dailyLimit = limit ?? 0;

  // 대상
  const target = value.targetType ?? "STOCK";
  const ruleTarget = rule.targetType ?? "STOCK";
  if (target !== ruleTarget) {
    patch.targetType = target;
    if (target === "STOCK") patch.stockId = value.stockId;
    else patch.targetGroupId = value.targetGroupId;
  } else if (target === "STOCK" && value.stockId !== rule.stockId) {
    patch.stockId = value.stockId;
  } else if (target === "GROUP" && value.targetGroupId !== rule.targetGroupId) {
    patch.targetGroupId = value.targetGroupId;
  }
  const targetChanged = patch.targetType !== undefined || patch.stockId !== undefined || patch.targetGroupId !== undefined;

  // 주문 유형
  const order = value.orderType ?? "MARKET";
  const ruleOrder = rule.orderType ?? "MARKET";
  if (order !== ruleOrder) {
    patch.orderType = order;
    if (order === "LIMIT") patch.limitOffsetBps = value.limitOffsetBps;
  } else if (order === "LIMIT" && value.limitOffsetBps !== rule.limitOffsetBps) {
    patch.limitOffsetBps = value.limitOffsetBps;
  }

  // 수량 기준
  const size = value.sizeType ?? "SHARES";
  const ruleSize = rule.sizeType ?? "SHARES";
  if (size !== ruleSize) {
    patch.sizeType = size;
    if (size === "SHARES") patch.quantity = value.quantity;
    else patch.equityPct = value.equityPct;
  } else if (size === "SHARES" && value.quantity !== rule.quantity) {
    patch.quantity = value.quantity;
  } else if (size === "EQUITY_PCT" && value.equityPct !== (rule.equityPct == null ? null : Number(rule.equityPct))) {
    patch.equityPct = value.equityPct;
  }

  // 그룹이 지워져 꺼진 규칙은 대상을 바꿀 때만 다시 켤 수 있다(ADR-098)
  if (opts.reactivate && targetChanged && rule.targetGroupMissing && !rule.isActive) patch.isActive = true;

  return patch;
}

/** 기준·대상·켜기를 건드리지 않는 PATCH — 응답을 기다리지 않고 목록에 먼저 반영해도 되는 것. */
const OPTIMISTIC_KEYS = new Set<keyof UpdateWatchRuleRequest>([
  "name", "minImportanceScore", "cooldownSec", "dailyLimit", "quantity", "limitOffsetBps", "equityPct",
]);

export function isOptimisticSafe(patch: UpdateWatchRuleRequest): boolean {
  const keys = Object.keys(patch) as (keyof UpdateWatchRuleRequest)[];
  return keys.length > 0 && keys.every((k) => OPTIMISTIC_KEYS.has(k));
}

/** isOptimisticSafe인 PATCH를 캐시의 규칙에 적용한다(서버 응답 전 표시용). 규칙의 기준과 맞지 않는 값은 반영하지 않는다. */
export function applyOptimisticPatch(rule: WatchRuleResponse, patch: UpdateWatchRuleRequest): WatchRuleResponse {
  const next = { ...rule };
  if (patch.name !== undefined) next.name = patch.name.trim() || null;
  if (patch.minImportanceScore !== undefined) next.minImportanceScore = patch.minImportanceScore;
  if (patch.cooldownSec !== undefined) next.cooldownSec = patch.cooldownSec;
  if (patch.dailyLimit !== undefined) next.dailyLimit = patch.dailyLimit === 0 ? null : patch.dailyLimit;
  if (patch.quantity !== undefined && (rule.sizeType ?? "SHARES") === "SHARES") next.quantity = patch.quantity;
  if (patch.equityPct !== undefined && rule.sizeType === "EQUITY_PCT") next.equityPct = patch.equityPct;
  if (patch.limitOffsetBps !== undefined && rule.orderType === "LIMIT") next.limitOffsetBps = patch.limitOffsetBps;
  return next;
}
