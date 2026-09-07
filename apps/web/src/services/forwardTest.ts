import type { ForwardTestResult } from "@monticker/types";
import { authFetch } from "./api";

export async function getForwardTestStatus(ruleSetId: string): Promise<ForwardTestResult | null> {
  const res = await authFetch(`/api/quant/rulesets/${ruleSetId}/forward-test`);
  if (res.status === 204) return null;
  if (!res.ok) throw new Error("포워드 테스트 상태 조회에 실패했습니다.");
  return res.json();
}

export async function startForwardTest(ruleSetId: string, stockId: number, initialCapital: number): Promise<ForwardTestResult> {
  const res = await authFetch(`/api/quant/rulesets/${ruleSetId}/forward-test/start`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ stockId, initialCapital }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message ?? "포워드 테스트 시작에 실패했습니다.");
  }
  return res.json();
}

export async function stopForwardTest(ruleSetId: string): Promise<ForwardTestResult> {
  const res = await authFetch(`/api/quant/rulesets/${ruleSetId}/forward-test/stop`, { method: "POST" });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message ?? "포워드 테스트 중지에 실패했습니다.");
  }
  return res.json();
}
