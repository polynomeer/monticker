import { useEffect } from "react";
import { Tabs, useRouter } from "expo-router";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { FloatingTabBar } from "@/components/shell/FloatingTabBar";
import { ONBOARDED_KEY } from "@/services/onboarding";

/** ADR-113 — 탭 3개(홈 · 관심종목 · 내 계좌). 탭 바는 떠 있는 유리 바 + 떨어진 검색 버튼 */
export default function TabsLayout() {
  const router = useRouter();
  // 처음 실행이면 온보딩 3장을 먼저 보인다
  useEffect(() => {
    AsyncStorage.getItem(ONBOARDED_KEY).then((v) => { if (v !== "1") router.replace("/onboarding"); }).catch(() => undefined);
  }, [router]);

  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(props) => <FloatingTabBar {...props} />}>
      <Tabs.Screen name="index" options={{ title: "홈" }} />
      <Tabs.Screen name="watchlist" options={{ title: "관심종목" }} />
      <Tabs.Screen name="account" options={{ title: "내 계좌" }} />
    </Tabs>
  );
}
