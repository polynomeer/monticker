import { Stack } from "expo-router";
import { useEffect } from "react";
import { StatusBar } from "react-native";
import { registerPushToken } from "@/services/notifications";
import { restoreSession } from "@/services/auth";
import { useAuthState } from "@/services/useAuth";

export default function RootLayout() {
  const auth = useAuthState();
  useEffect(() => { restoreSession(); }, []);
  // 서버는 로그인한 사용자의 푸시 토큰만 저장한다 — 로그인된 뒤에 등록한다
  useEffect(() => { if (auth === "signedIn") registerPushToken(); }, [auth]);

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
        <Stack.Screen name="login"           options={{ title: "로그인", presentation: "modal" }} />
      </Stack>
    </>
  );
}
