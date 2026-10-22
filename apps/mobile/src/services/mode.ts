import { useSyncExternalStore } from "react";
import AsyncStorage from "@react-native-async-storage/async-storage";
import type { BrokerageAccountResponse } from "@monticker/types";
import { authFetch, getAuthState, subscribeAuth } from "./auth";

/**
 * ADR-113 — 앱 전체 모의/실전 모드. 내 계좌의 모의 | 실전 분할 버튼만 바꾼다(주문 시트에서는 못 바꾼다).
 * 실전은 주문용 증권사 연동이 살아 있을 때만 고를 수 있다. 앱을 다시 열거나 로그아웃하면 연동을 다시 확인하고,
 * 끊겼으면 모의로 돌린다.
 */

export type TradingMode = "paper" | "live";

const KEY = "monticker.tradingMode";

let mode: TradingMode = "paper";
let broker: BrokerageAccountResponse | null = null;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => { listeners.delete(l); };
}

/** 연동 계좌를 읽는다. 없거나 토큰이 죽었으면 null */
export async function loadBrokerAccount(): Promise<BrokerageAccountResponse | null> {
  if (getAuthState() !== "signedIn") { broker = null; emit(); return null; }
  try {
    const res = await authFetch("/api/brokerage/account");
    broker = res.ok ? ((await res.json()) as BrokerageAccountResponse) : null;
  } catch {
    broker = null;
  }
  if (broker && (!broker.isActive || !broker.tokenValid)) broker = null;
  emit();
  return broker;
}

/** 실전으로 바꾸려면 연동을 확인한다. 못 바꾸면 false — 화면이 연동 안내를 띄운다 */
export async function setTradingMode(next: TradingMode): Promise<boolean> {
  if (next === "live" && !(await loadBrokerAccount())) return false;
  mode = next;
  emit();
  AsyncStorage.setItem(KEY, next).catch(() => undefined);
  return true;
}

/** 앱 시작 때 한 번 — 저장된 실전은 연동이 살아 있을 때만 되살린다 */
export async function restoreTradingMode(): Promise<void> {
  const saved = await AsyncStorage.getItem(KEY).catch(() => null);
  if (saved === "live" && (await loadBrokerAccount())) {
    mode = "live";
    emit();
  }
}

subscribeAuth((s) => {
  if (s === "signedOut" && mode === "live") {
    mode = "paper";
    broker = null;
    emit();
    AsyncStorage.setItem(KEY, "paper").catch(() => undefined);
  }
});

export function useTradingMode(): { mode: TradingMode; broker: BrokerageAccountResponse | null } {
  const m = useSyncExternalStore(subscribe, () => mode);
  const b = useSyncExternalStore(subscribe, () => broker);
  return { mode: m, broker: b };
}

/** 화면에 보일 증권사 이름 */
export function brokerName(provider: string | undefined): string {
  switch (provider) {
    case "KIS": return "한국투자증권";
    case "TOSS": return "토스증권";
    default: return provider ?? "증권사";
  }
}
