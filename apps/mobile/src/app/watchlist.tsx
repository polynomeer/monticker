import { useEffect, useState, useCallback } from "react";
import { View, Text, FlatList, StyleSheet, ActivityIndicator, TouchableOpacity, RefreshControl } from "react-native";
import { useRouter } from "expo-router";
import { getApiBase } from "@/services/api";

interface WatchlistItem {
  id: number;
  stockId: number;
  symbol: string;
  name: string;
  currentPrice?: number;
  changeRate?: number;
}
interface WatchlistGroup { id: number; name: string; items: WatchlistItem[] }

export default function WatchlistScreen() {
  const [groups, setGroups]     = useState<WatchlistGroup[]>([]);
  const [loading, setLoading]   = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const router = useRouter();

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const res = await fetch(`${getApiBase()}/api/watchlists`);
      if (res.ok) setGroups(await res.json());
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const allItems = groups.flatMap(g => g.items.map(i => ({ ...i, groupName: g.name })));

  if (loading) return <View style={s.center}><ActivityIndicator size="large" color={ACCENT} /></View>;

  return (
    <View style={s.container}>
      {allItems.length === 0 ? (
        <View style={s.center}>
          <Text style={s.emptyIcon}>⭐</Text>
          <Text style={s.emptyTitle}>관심종목이 없어요</Text>
          <Text style={s.emptySub}>홈에서 종목을 검색하고 추가해보세요</Text>
          <TouchableOpacity style={s.cta} onPress={() => router.push("/")}>
            <Text style={s.ctaText}>종목 둘러보기</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <FlatList
          data={allItems}
          keyExtractor={i => i.id.toString()}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} tintColor={ACCENT} />}
          ItemSeparatorComponent={() => <View style={s.sep} />}
          renderItem={({ item }) => {
            const pos = (item.changeRate ?? 0) >= 0;
            return (
              <TouchableOpacity style={s.row} onPress={() => router.push(`/stocks/${item.symbol}`)}>
                <View style={s.left}>
                  <Text style={s.symbol}>{item.symbol}</Text>
                  <Text style={s.name} numberOfLines={1}>{item.name}</Text>
                </View>
                {item.currentPrice != null && (
                  <View style={s.right}>
                    <Text style={s.price}>₩{item.currentPrice.toLocaleString()}</Text>
                    <Text style={[s.change, pos ? s.pos : s.neg]}>
                      {pos ? "+" : ""}{(item.changeRate ?? 0).toFixed(2)}%
                    </Text>
                  </View>
                )}
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
        <TouchableOpacity style={s.navItem}>
          <Text style={[s.navIcon, s.navActive]}>⭐</Text><Text style={[s.navLabel, s.navActive]}>관심종목</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.navItem} onPress={() => router.push("/alerts")}>
          <Text style={s.navIcon}>🔔</Text><Text style={s.navLabel}>알림</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const BG = "#0d1117", SURF = "#161929", BORDER = "#242840", TEXT = "#e8eaf6", MUTED = "#5c6288", ACCENT = "#5c7cfa";

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  center:    { flex: 1, alignItems: "center", justifyContent: "center", padding: 32 },
  emptyIcon: { fontSize: 48, marginBottom: 16 },
  emptyTitle:{ color: TEXT, fontSize: 18, fontWeight: "700", marginBottom: 8 },
  emptySub:  { color: MUTED, fontSize: 14, textAlign: "center", marginBottom: 24 },
  cta:       { backgroundColor: ACCENT, paddingHorizontal: 24, paddingVertical: 12, borderRadius: 8 },
  ctaText:   { color: "#fff", fontWeight: "600", fontSize: 15 },
  sep:       { height: 1, backgroundColor: BORDER, marginHorizontal: 16 },
  row:       { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingHorizontal: 16, paddingVertical: 14 },
  left:      { flex: 1 },
  right:     { alignItems: "flex-end" },
  symbol:    { color: TEXT, fontWeight: "700", fontSize: 15 },
  name:      { color: MUTED, fontSize: 12, marginTop: 2 },
  price:     { color: TEXT, fontWeight: "600", fontSize: 15 },
  change:    { fontSize: 12, marginTop: 2 },
  pos:       { color: "#ff5370" },
  neg:       { color: "#4fc3f7" },
  nav:       { flexDirection: "row", backgroundColor: SURF, borderTopWidth: 1, borderTopColor: BORDER, paddingBottom: 20, paddingTop: 8 },
  navItem:   { flex: 1, alignItems: "center", gap: 2 },
  navIcon:   { fontSize: 20 },
  navLabel:  { color: MUTED, fontSize: 10 },
  navActive: { color: ACCENT },
});
