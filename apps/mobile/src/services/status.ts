import { useCallback, useEffect, useState } from "react";
import { AppState } from "react-native";
import type { BrokerageOrderResponse, RuleSet } from "@monticker/types";
import { authFetch } from "./auth";
import { useAuthState } from "./useAuth";
import { useTradingMode, type TradingMode } from "./mode";

/**
 * 상태 바(ADR-113)의 "진행 중인 것" — 실행 중 전략 수와 미체결 주문 수. 잔고는 읽지 않는다.
 * 합친 API가 없어 쿼리 두 개를 쓴다. 30초마다, 그리고 앱이 앞으로 올 때 다시 읽는다.
 */

export interface StatusSummary {
  runningStrategies: number;
  openOrders: number;
}

const OPEN_LIVE: BrokerageOrderResponse["status"][] = ["PENDING_SUBMIT", "SUBMITTED", "PARTIALLY_FILLED", "UNKNOWN"];
const REFRESH_MS = 30_000;

async function count(mode: TradingMode): Promise<StatusSummary | null> {
  const [rules, orders] = await Promise.all([
    authFetch("/api/quant/rulesets"),
    mode === "live" ? authFetch("/api/brokerage/orders") : authFetch("/api/risk/exposure"),
  ]);
  if (!rules.ok || !orders.ok) return null;
  const list = (await rules.json()) as RuleSet[];
  const runningStrategies = list.filter((r) => r.status === "RUNNING").length;
  if (mode === "live") {
    const live = (await orders.json()) as BrokerageOrderResponse[];
    return { runningStrategies, openOrders: live.filter((o) => OPEN_LIVE.includes(o.status)).length };
  }
  const exposure = (await orders.json()) as { activeOrderCount: number };
  return { runningStrategies, openOrders: exposure.activeOrderCount };
}

export function useStatusSummary(): StatusSummary | null {
  const auth = useAuthState();
  const { mode } = useTradingMode();
  const [summary, setSummary] = useState<StatusSummary | null>(null);

  const load = useCallback(() => {
    if (auth !== "signedIn") { setSummary(null); return; }
    count(mode).then(setSummary).catch(() => setSummary(null));
  }, [auth, mode]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    const sub = AppState.addEventListener("change", (s) => { if (s === "active") load(); });
    return () => { clearInterval(timer); sub.remove(); };
  }, [load]);

  return summary;
}

/** 모의는 진행 중인 것이 있을 때만, 실전은 늘 보인다 */
export function shouldShowStatus(mode: TradingMode, summary: StatusSummary | null): boolean {
  if (mode === "live") return true;
  return !!summary && (summary.runningStrategies > 0 || summary.openOrders > 0);
}
