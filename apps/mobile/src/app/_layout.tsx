import { Stack } from "expo-router";
import { useEffect } from "react";
import { StatusBar } from "react-native";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { registerPushToken } from "@/services/notifications";
import { restoreSession } from "@/services/auth";
import { useAuthState } from "@/services/useAuth";
import { ThemeProvider, useTheme } from "@/theme/ThemeProvider";
import { restoreTradingMode } from "@/services/mode";

export default function RootLayout() {
  const auth = useAuthState();
  useEffect(() => { restoreSession(); }, []);
  // 서버는 로그인한 사용자의 푸시 토큰만 저장한다 — 로그인된 뒤에 등록한다
  useEffect(() => { if (auth === "signedIn") registerPushToken(); }, [auth]);
  // 저장된 실전 모드는 증권사 연동이 살아 있을 때만 되살린다(ADR-113)
  useEffect(() => { if (auth === "signedIn") restoreTradingMode(); }, [auth]);

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
        <Stack.Screen name="(tabs)"          options={{ headerShown: false }} />
        <Stack.Screen name="search"          options={{ headerShown: false, animation: "fade" }} />
        <Stack.Screen name="settings/index"  options={{ headerShown: false }} />
        <Stack.Screen name="settings/updown" options={{ headerShown: false }} />
        <Stack.Screen name="alerts"          options={{ headerShown: false }} />
        <Stack.Screen name="accounts"        options={{ title: "다른 증권 계좌" }} />
        <Stack.Screen name="stocks/[symbol]" options={{ title: "" }} />
        <Stack.Screen name="login"           options={{ title: "로그인", presentation: "modal" }} />
      </Stack>
    </>
  );
}
