import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import Constants from "expo-constants";
import { Platform } from "react-native";
import { getApiBase } from "./api";

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
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

async function registerTokenWithServer(token: string): Promise<void> {
  try {
    await fetch(`${getApiBase()}/api/devices/push-token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token, platform: Platform.OS }),
    });
  } catch {
    // non-critical — server registration will be retried on next app open
  }
}
