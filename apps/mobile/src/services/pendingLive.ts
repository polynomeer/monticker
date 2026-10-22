import { useSyncExternalStore } from "react";
import AsyncStorage from "@react-native-async-storage/async-storage";

/**
 * 결과 불명인 실전 주문(ADR-113 — 다시 보내지 않는다). 시트를 닫았다 열어도 같은 종목·방향 주문을 막도록 기기에 남긴다.
 * 사용자가 주문 내역에서 체결 여부를 확인했다고 누르면 지운다.
 */
const KEY = "monticker.live.pendingUnknown";
let pending: Record<string, string> = {};
const listeners = new Set<() => void>();
AsyncStorage.getItem(KEY).then((raw) => { if (raw) { pending = JSON.parse(raw); listeners.forEach((l) => l()); } }).catch(() => undefined);

function save() {
  pending = { ...pending };
  listeners.forEach((l) => l());
  AsyncStorage.setItem(KEY, JSON.stringify(pending)).catch(() => undefined);
}

const id = (symbol: string, side: string) => `${symbol}:${side}`;

export function markPendingUnknown(symbol: string, side: string) {
  pending[id(symbol, side)] = new Date().toISOString();
  save();
}

export function clearPendingUnknown(symbol: string, side: string) {
  delete pending[id(symbol, side)];
  save();
}

/** 결과 불명으로 남은 시각. 없으면 null */
export function usePendingUnknown(symbol: string, side: string): string | null {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => { listeners.delete(l); }; },
    () => pending[id(symbol, side)] ?? null,
  );
}
