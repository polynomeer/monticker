import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import Constants from "expo-constants";
import { Platform } from "react-native";
import { authFetch } from "./auth";

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    // SDK 53+: shouldShowAlert(deprecated)를 배너·알림 목록 두 가지로 나눴다 — 예전 true와 같게 둘 다 켠다.
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

export async function registerPushToken(): Promise<void> {
  if (!Device.isDevice) {
    // simulator — skip silently
    return;
  }

  const { status: existingStatus } = await Notifications.getPermissionsAsync();
  let finalStatus = existingStatus;

  if (existingStatus !== "granted") {
    const { status } = await Notifications.requestPermissionsAsync();
    finalStatus = status;
  }

  if (finalStatus !== "granted") {
    return;
  }

  if (Platform.OS === "android") {
    await Notifications.setNotificationChannelAsync("alerts", {
      name: "주가 알림",
      importance: Notifications.AndroidImportance.HIGH,
    });
  }

  const projectId = Constants.expoConfig?.extra?.eas?.projectId ?? Constants.easConfig?.projectId;
  if (!projectId) return;

  const token = (await Notifications.getExpoPushTokenAsync({ projectId })).data;
  await registerTokenWithServer(token);
}

/** 지금 기기의 Expo 푸시 토큰 — 시뮬레이터·권한 없음·프로젝트 ID 없음이면 null */
async function currentPushToken(): Promise<string | null> {
  if (!Device.isDevice) return null;
  const { status } = await Notifications.getPermissionsAsync();
  if (status !== "granted") return null;
  const projectId = Constants.expoConfig?.extra?.eas?.projectId ?? Constants.easConfig?.projectId;
  if (!projectId) return null;
  return (await Notifications.getExpoPushTokenAsync({ projectId })).data;
}

/**
 * 서버는 로그인한 사용자의 토큰만 저장한다(DeviceController — 로그인 없이 보내면 조용히 버린다). 그래서 로그인한 뒤에
 * 부른다. 예전에는 인증 없이 보내 한 번도 저장되지 않았다.
 */
async function registerTokenWithServer(token: string): Promise<void> {
  try {
    await authFetch("/api/devices/push-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token, platform: Platform.OS }),
    });
  } catch {
    // 다음에 앱을 열 때 다시 등록한다
  }
}

/** 로그아웃 전에 — 이 기기로 더는 푸시가 오지 않게 한다(세션이 살아 있는 동안 불러야 한다) */
export async function unregisterPushToken(): Promise<void> {
  const token = await currentPushToken().catch(() => null);
  if (!token) return;
  await authFetch("/api/devices/push-token", {
    method: "DELETE",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, platform: Platform.OS }),
  }).catch(() => undefined);
}
