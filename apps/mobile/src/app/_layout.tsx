import { Stack } from "expo-router";
import { useEffect } from "react";
import { StatusBar } from "react-native";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { registerPushToken } from "@/services/notifications";
import { restoreSession } from "@/services/auth";
import { useAuthState } from "@/services/useAuth";
import { ThemeProvider, useTheme } from "@/theme/ThemeProvider";

export default function RootLayout() {
  const auth = useAuthState();
  useEffect(() => { restoreSession(); }, []);
  // 서버는 로그인한 사용자의 푸시 토큰만 저장한다 — 로그인된 뒤에 등록한다
  useEffect(() => { if (auth === "signedIn") registerPushToken(); }, [auth]);

  return (
    <SafeAreaProvider>
      <ThemeProvider>
        <ThemedStack />
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

function ThemedStack() {
  const { c, scheme } = useTheme();
  return (
    <>
      <StatusBar barStyle={scheme === "dark" ? "light-content" : "dark-content"} backgroundColor={c.bg} />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: c.bg },
          headerTintColor: c.text,
          headerTitleStyle: { fontWeight: "700" },
          headerShadowVisible: false,
          contentStyle: { backgroundColor: c.bg },
        }}
      >
        <Stack.Screen name="index"           options={{ title: "monticker" }} />
        <Stack.Screen name="watchlist"       options={{ title: "관심종목" }} />
        <Stack.Screen name="alerts"          options={{ title: "알림" }} />
        <Stack.Screen name="accounts"        options={{ title: "내 계좌" }} />
        <Stack.Screen name="stocks/[symbol]" options={{ title: "" }} />
        <Stack.Screen name="login"           options={{ title: "로그인", presentation: "modal" }} />
      </Stack>
    </>
  );
}
