import { Stack } from "expo-router";
import { useEffect } from "react";
import { registerPushToken } from "@/services/notifications";

export default function RootLayout() {
  useEffect(() => {
    registerPushToken();
  }, []);

  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: "monticker" }} />
      <Stack.Screen name="watchlist" options={{ title: "관심종목" }} />
      <Stack.Screen name="stocks/[symbol]" options={{ title: "" }} />
    </Stack>
  );
}
