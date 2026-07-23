import { Stack } from "expo-router";
import { useEffect } from "react";
import { StatusBar } from "react-native";
import { registerPushToken } from "@/services/notifications";

export default function RootLayout() {
  useEffect(() => {
    registerPushToken();
  }, []);

  return (
    <>
      <StatusBar barStyle="light-content" backgroundColor="#0d1117" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: "#0d1117" },
          headerTintColor: "#e8eaf6",
          headerTitleStyle: { fontWeight: "700" },
          contentStyle: { backgroundColor: "#0d1117" },
        }}
      >
        <Stack.Screen name="index"           options={{ title: "monticker" }} />
        <Stack.Screen name="watchlist"       options={{ title: "관심종목" }} />
        <Stack.Screen name="alerts"          options={{ title: "알림" }} />
        <Stack.Screen name="stocks/[symbol]" options={{ title: "" }} />
      </Stack>
    </>
  );
}
