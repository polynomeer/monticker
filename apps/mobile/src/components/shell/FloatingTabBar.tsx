import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { LinearGradientFade } from "./Fade";
import { useTheme } from "@/theme/ThemeProvider";
import { Glass, Icon, type IconName } from "@/components/ui";
import { StatusPill } from "./StatusPill";

/**
 * ADR-113 — 하단 떠 있는 유리 탭 바(홈 · 관심종목 · 내 계좌) + 오른쪽에 떨어진 검색 버튼, 그 위 상태 바.
 * expo-router `Tabs`의 tabBar로 쓴다. 상태 바는 내 계좌 탭에서 숨긴다(같은 내용이 화면에 있다).
 */

const TABS: { name: string; label: string; icon: IconName }[] = [
  { name: "index", label: "홈", icon: "home" },
  { name: "watchlist", label: "관심종목", icon: "star" },
  { name: "account", label: "내 계좌", icon: "wallet" },
];

interface TabBarProps {
  state: { index: number; routes: { key: string; name: string }[] };
  navigation: { emit: (e: { type: "tabPress"; target: string; canPreventDefault: true }) => { defaultPrevented: boolean }; navigate: (name: string) => void };
}

export function FloatingTabBar({ state, navigation }: TabBarProps) {
  const { c } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const current = state.routes[state.index]?.name;

  return (
    <View style={[s.wrap, { paddingBottom: Math.max(insets.bottom, 16) + 14 }]} pointerEvents="box-none">
      <LinearGradientFade color={c.bg} />
      {current !== "account" && <StatusPill />}
      <View style={s.bar} accessibilityRole="tablist" accessibilityLabel="주 메뉴">
        <Glass style={s.tabs}>
          <View style={s.tabsRow}>
            {TABS.map((t) => {
              const route = state.routes.find((r) => r.name === t.name);
              const focused = current === t.name;
              const onPress = () => {
                if (!route) return;
                const e = navigation.emit({ type: "tabPress", target: route.key, canPreventDefault: true });
                if (!focused && !e.defaultPrevented) navigation.navigate(t.name);
              };
              return (
                <Pressable
                  key={t.name}
                  onPress={onPress}
                  accessibilityRole="tab"
                  accessibilityState={{ selected: focused }}
                  accessibilityLabel={t.label}
                  style={[s.tab, focused && { backgroundColor: c.glassActive }]}
                >
                  <Icon name={t.icon} size={24} color={focused ? c.accent : c.soft} strokeWidth={1.9} />
                  <Text style={[s.tabLabel, { color: focused ? c.accent : c.soft }]}>{t.label}</Text>
                </Pressable>
              );
            })}
          </View>
        </Glass>
        <Glass style={s.search}>
          <Pressable onPress={() => router.push("/search")} accessibilityRole="search" accessibilityLabel="검색" style={s.searchHit}>
            <Icon name="search" size={24} color={c.body} strokeWidth={2.1} />
          </Pressable>
        </Glass>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  wrap: { position: "absolute", left: 0, right: 0, bottom: 0, paddingHorizontal: 20, gap: 8 },
  bar: { flexDirection: "row", gap: 10, height: 64 },
  tabs: { flex: 1, borderRadius: 32, padding: 4 },
  tabsRow: { flex: 1, flexDirection: "row", gap: 2 },
  tab: { flex: 1, borderRadius: 28, alignItems: "center", justifyContent: "center", gap: 2 },
  tabLabel: { fontSize: 10, fontWeight: "600" },
  search: { width: 64, borderRadius: 32 },
  searchHit: { flex: 1, alignItems: "center", justifyContent: "center" },
});
