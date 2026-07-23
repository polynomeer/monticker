"use client";
import {
  View, Text, FlatList, TouchableOpacity,
  StyleSheet, ActivityIndicator, RefreshControl,
} from "react-native";
import { useRouter } from "expo-router";
import { useEffect, useState, useCallback } from "react";
import { getApiBase } from "@/services/api";

interface ScreenerItem {
  stockId: number;
  symbol: string;
  name: string;
  market: string;
  currentPrice: number;
  changeRate: number;
  volume: number;
  tradeAmount: number;
}

const TABS = [
  { key: "amount", label: "거래대금" },
  { key: "volume", label: "거래량" },
  { key: "rise",   label: "급상승" },
  { key: "fall",   label: "급하락" },
];

export default function HomeScreen() {
  const router = useRouter();
  const [sort, setSort]         = useState("amount");
  const [items, setItems]       = useState<ScreenerItem[]>([]);
  const [loading, setLoading]   = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    try {
      const res = await fetch(`${getApiBase()}/api/screener?sort=${sort}&page=0&size=50`);
      if (res.ok) {
        const data = await res.json();
        setItems(data.items ?? data.content ?? []);
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [sort]);

  useEffect(() => { load(); }, [load]);

  const renderItem = ({ item }: { item: ScreenerItem }) => {
    const positive = item.changeRate >= 0;
    return (
      <TouchableOpacity style={s.row} onPress={() => router.push(`/stocks/${item.symbol}`)}>
        <View style={s.rowLeft}>
          <Text style={s.symbol}>{item.symbol}</Text>
          <Text style={s.name} numberOfLines={1}>{item.name}</Text>
        </View>
        <View style={s.rowRight}>
          <Text style={s.price}>₩{item.currentPrice.toLocaleString()}</Text>
          <Text style={[s.change, positive ? s.pos : s.neg]}>
            {positive ? "+" : ""}{item.changeRate.toFixed(2)}%
          </Text>
        </View>
      </TouchableOpacity>
    );
  };

  return (
    <View style={s.container}>
      {/* 탭 */}
      <View style={s.tabs}>
        {TABS.map(t => (
          <TouchableOpacity key={t.key} style={[s.tab, sort === t.key && s.tabActive]} onPress={() => setSort(t.key)}>
            <Text style={[s.tabText, sort === t.key && s.tabTextActive]}>{t.label}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {loading ? (
        <View style={s.center}><ActivityIndicator color="#5c7cfa" size="large" /></View>
      ) : (
        <FlatList
          data={items}
          keyExtractor={i => i.stockId.toString()}
          renderItem={renderItem}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} tintColor="#5c7cfa" />}
          ListEmptyComponent={<View style={s.center}><Text style={s.empty}>종목 데이터가 없습니다</Text></View>}
          ItemSeparatorComponent={() => <View style={s.sep} />}
        />
      )}

      {/* 하단 내비게이션 */}
      <View style={s.nav}>
        <TouchableOpacity style={s.navItem}>
          <Text style={[s.navIcon, s.navActive]}>📈</Text>
          <Text style={[s.navLabel, s.navActive]}>홈</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.navItem} onPress={() => router.push("/watchlist")}>
          <Text style={s.navIcon}>⭐</Text>
          <Text style={s.navLabel}>관심종목</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.navItem} onPress={() => router.push("/alerts")}>
          <Text style={s.navIcon}>🔔</Text>
          <Text style={s.navLabel}>알림</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const BG    = "#0d1117";
const SURF  = "#161929";
const BORDER= "#242840";
const TEXT  = "#e8eaf6";
const MUTED = "#5c6288";
const ACCENT= "#5c7cfa";

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  center:    { flex: 1, alignItems: "center", justifyContent: "center", paddingVertical: 60 },
  empty:     { color: MUTED, fontSize: 14 },
  sep:       { height: 1, backgroundColor: BORDER, marginHorizontal: 16 },

  tabs:     { flexDirection: "row", padding: 12, gap: 8, backgroundColor: SURF, borderBottomWidth: 1, borderBottomColor: BORDER },
  tab:      { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 20, backgroundColor: BORDER },
  tabActive:{ backgroundColor: ACCENT },
  tabText:  { color: MUTED, fontSize: 12, fontWeight: "600" },
  tabTextActive: { color: "#fff" },

  row:      { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingHorizontal: 16, paddingVertical: 14 },
  rowLeft:  { flex: 1 },
  rowRight: { alignItems: "flex-end" },
  symbol:   { color: TEXT, fontWeight: "700", fontSize: 15 },
  name:     { color: MUTED, fontSize: 12, marginTop: 2 },
  price:    { color: TEXT, fontWeight: "600", fontSize: 15, fontVariant: ["tabular-nums"] },
  change:   { fontSize: 12, marginTop: 2, fontVariant: ["tabular-nums"] },
  pos:      { color: "#ff5370" },
  neg:      { color: "#4fc3f7" },

  nav:      { flexDirection: "row", backgroundColor: SURF, borderTopWidth: 1, borderTopColor: BORDER, paddingBottom: 20, paddingTop: 8 },
  navItem:  { flex: 1, alignItems: "center", gap: 2 },
  navIcon:  { fontSize: 20 },
  navLabel: { color: MUTED, fontSize: 10 },
  navActive:{ color: ACCENT },
});
