import { useSyncExternalStore } from "react";
import AsyncStorage from "@react-native-async-storage/async-storage";
import * as LocalAuthentication from "expo-local-authentication";

/**
 * 설정 › 보안 "Face ID로 잠금". 켜 두면 앱을 새로 열거나 백그라운드에서 돌아올 때 생체 인증(없으면 기기 암호)을 묻는다.
 * 설정값만 기기에 둔다. 서버 토큰과는 무관하다 — 잠금은 화면을 가리는 것이고 세션은 그대로다.
 */
const KEY = "monticker.appLock";
let enabled = false;
let loaded = false;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

AsyncStorage.getItem(KEY)
  .then((v) => { enabled = v === "on"; loaded = true; emit(); })
  .catch(() => { loaded = true; emit(); });

export function useAppLockEnabled(): { enabled: boolean; loaded: boolean } {
  const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };
  return { enabled: useSyncExternalStore(subscribe, () => enabled), loaded: useSyncExternalStore(subscribe, () => loaded) };
}

/** 이 기기에서 쓸 수 있는지(생체 정보가 등록돼 있는지) */
export async function canUseAppLock(): Promise<boolean> {
  try {
    return (await LocalAuthentication.hasHardwareAsync()) && (await LocalAuthentication.isEnrolledAsync());
  } catch {
    return false;
  }
}

export async function authenticate(reason: string): Promise<boolean> {
  try {
    const r = await LocalAuthentication.authenticateAsync({ promptMessage: reason, cancelLabel: "취소" });
    return r.success;
  } catch {
    return false;
  }
}

/** 켤 때는 한 번 인증해 본다 — 본인이 쓸 수 있는지 확인 */
export async function setAppLock(on: boolean): Promise<boolean> {
  if (on && !(await authenticate("잠금을 켜려면 인증해 주세요"))) return false;
  enabled = on;
  emit();
  await AsyncStorage.setItem(KEY, on ? "on" : "off").catch(() => undefined);
  return true;
}
