import { Tabs } from "expo-router";
import { FloatingTabBar } from "@/components/shell/FloatingTabBar";

/** ADR-113 — 탭 3개(홈 · 관심종목 · 내 계좌). 탭 바는 떠 있는 유리 바 + 떨어진 검색 버튼 */
export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(props) => <FloatingTabBar {...props} />}>
      <Tabs.Screen name="index" options={{ title: "홈" }} />
      <Tabs.Screen name="watchlist" options={{ title: "관심종목" }} />
      <Tabs.Screen name="account" options={{ title: "내 계좌" }} />
    </Tabs>
  );
}
