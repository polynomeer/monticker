import { useEffect, useState, useCallback } from "react";
import { View, Text, FlatList, StyleSheet, ActivityIndicator, TouchableOpacity, RefreshControl } from "react-native";
import { useRouter } from "expo-router";
import { getApiBase } from "@/services/api";

interface AlertRule {
  id: number;
  stockId: number;
  symbol: string;
  name: string;
  ruleType: string;
  conditionJson: string;
  isActive: boolean;
}

const RULE_LABEL: Record<string, string> = {
  PRICE_ABOVE:   "목표가 이상",
  PRICE_BELOW:   "목표가 이하",
  VOLUME_SURGE:  "거래량 급등",
};

export default function AlertsScreen() {
  const [rules, setRules]       = useState<AlertRule[]>([]);
  const [loading, setLoading]   = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const router = useRouter();

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const res = await fetch(`${getApiBase()}/api/alerts/rules`, {
        headers: { "Content-Type": "application/json" },
      });
      if (res.ok) setRules(await res.json());
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <View style={s.center}><ActivityIndicator size="large" color={ACCENT} /></View>;

  return (
    <View style={s.container}>
      {rules.length === 0 ? (
        <View style={s.center}>
          <Text style={s.emptyIcon}>🔔</Text>
          <Text style={s.emptyTitle}>설정된 알림이 없어요</Text>
          <Text style={s.emptySub}>종목을 선택하고 목표가 알림을 설정해보세요</Text>
          <TouchableOpacity style={s.cta} onPress={() => router.push("/")}>
            <Text style={s.ctaText}>종목 찾기</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <FlatList
          data={rules}
          keyExtractor={r => r.id.toString()}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} tintColor={ACCENT} />}
          ItemSeparatorComponent={() => <View style={s.sep} />}
          renderItem={({ item }) => {
            const cond = (() => {
              try { return JSON.parse(item.conditionJson); } catch { return {}; }
            })();
            return (
              <TouchableOpacity style={s.row} onPress={() => router.push(`/stocks/${item.symbol}`)}>
                <View style={s.rowLeft}>
                  <View style={s.rowTop}>
                    <Text style={s.symbol}>{item.symbol}</Text>
                    <View style={[s.badge, item.isActive ? s.badgeOn : s.badgeOff]}>
                      <Text style={s.badgeText}>{item.isActive ? "활성" : "비활성"}</Text>
                    </View>
                  </View>
                  <Text style={s.ruleName}>{RULE_LABEL[item.ruleType] ?? item.ruleType}</Text>
                  {cond.threshold != null && (
                    <Text style={s.threshold}>₩{Number(cond.threshold).toLocaleString()}</Text>
                  )}
                </View>
              </TouchableOpacity>
            );
          }}
        />
      )}

      {/* 하단 내비게이션 */}
      <View style={s.nav}>
        <TouchableOpacity style={s.navItem} onPress={() => router.push("/")}>
          <Text style={s.navIcon}>📈</Text><Text style={s.navLabel}>홈</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.navItem} onPress={() => router.push("/watchlist")}>
          <Text style={s.navIcon}>⭐</Text><Text style={s.navLabel}>관심종목</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.navItem}>
          <Text style={[s.navIcon, s.navActive]}>🔔</Text><Text style={[s.navLabel, s.navActive]}>알림</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const BG = "#0d1117", SURF = "#161929", BORDER = "#242840", TEXT = "#e8eaf6", MUTED = "#5c6288", ACCENT = "#5c7cfa";

const s = StyleSheet.create({
  container:  { flex: 1, backgroundColor: BG },
  center:     { flex: 1, alignItems: "center", justifyContent: "center", padding: 32 },
  emptyIcon:  { fontSize: 48, marginBottom: 16 },
  emptyTitle: { color: TEXT, fontSize: 18, fontWeight: "700", marginBottom: 8 },
  emptySub:   { color: MUTED, fontSize: 14, textAlign: "center", marginBottom: 24 },
  cta:        { backgroundColor: ACCENT, paddingHorizontal: 24, paddingVertical: 12, borderRadius: 8 },
  ctaText:    { color: "#fff", fontWeight: "600", fontSize: 15 },
  sep:        { height: 1, backgroundColor: BORDER, marginHorizontal: 16 },
  row:        { paddingHorizontal: 16, paddingVertical: 14 },
  rowLeft:    { flex: 1 },
  rowTop:     { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 4 },
  symbol:     { color: TEXT, fontWeight: "700", fontSize: 15 },
  ruleName:   { color: MUTED, fontSize: 13 },
  threshold:  { color: ACCENT, fontSize: 14, fontWeight: "600", marginTop: 2 },
  badge:      { paddingHorizontal: 7, paddingVertical: 2, borderRadius: 4 },
  badgeOn:    { backgroundColor: "rgba(92,124,250,.2)" },
  badgeOff:   { backgroundColor: "#242840" },
  badgeText:  { color: TEXT, fontSize: 10, fontWeight: "700" },
  nav:        { flexDirection: "row", backgroundColor: SURF, borderTopWidth: 1, borderTopColor: BORDER, paddingBottom: 20, paddingTop: 8 },
  navItem:    { flex: 1, alignItems: "center", gap: 2 },
  navIcon:    { fontSize: 20 },
  navLabel:   { color: MUTED, fontSize: 10 },
  navActive:  { color: ACCENT },
});
