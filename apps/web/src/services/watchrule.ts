import type {
  CreateWatchRuleRequest,
  UpdateWatchRuleRequest,
  WatchRuleExecutionResponse,
  WatchRuleResponse,
} from "@monticker/types";
import { authFetch } from "./api";
import { ApiError } from "./brokerage";

async function throwIfNotOk(res: Response): Promise<void> {
  if (res.ok) return;
  const body = await res.json().catch(() => null);
  throw new ApiError(res.status, body?.message ?? "요청을 처리하지 못했습니다.", body?.detail ?? null);
}

export async function getWatchRules(): Promise<WatchRuleResponse[]> {
  const res = await authFetch("/api/watch-rules");
  await throwIfNotOk(res);
  return res.json();
}

export async function createWatchRule(req: CreateWatchRuleRequest): Promise<WatchRuleResponse> {
  const res = await authFetch("/api/watch-rules", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  await throwIfNotOk(res);
  return res.json();
}

export async function updateWatchRule(ruleId: number, req: UpdateWatchRuleRequest): Promise<WatchRuleResponse> {
  const res = await authFetch(`/api/watch-rules/${ruleId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  await throwIfNotOk(res);
  return res.json();
}

export async function deleteWatchRule(ruleId: number): Promise<void> {
  const res = await authFetch(`/api/watch-rules/${ruleId}`, { method: "DELETE" });
  await throwIfNotOk(res);
}

export async function getWatchRuleExecutions(limit = 50): Promise<WatchRuleExecutionResponse[]> {
  const res = await authFetch(`/api/watch-rules/executions?limit=${limit}`);
  await throwIfNotOk(res);
  return res.json();
}
