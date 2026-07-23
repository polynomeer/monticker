import { useEffect, useState, useCallback } from "react";
import { View, Text, StyleSheet, ScrollView, ActivityIndicator, TouchableOpacity, RefreshControl } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { getApiBase } from "@/services/api";

interface StockPrice { stockId: number; symbol: string; price: number; volume: number; changeRate?: number; hasData: boolean }
interface StockEvent { id: number; eventType: string; title: string; importanceScore: number; eventTime: string }
interface NewsItem   { id: number; title: string; sentiment: string; publishedAt: string; source: string }

const EVENT_COLOR: Record<string, string> = {
  PRICE_SPIKE:          "#ff5370",
  PRICE_DROP:           "#4fc3f7",
  VOLUME_SURGE:         "#5c7cfa",
  DISCLOSURE_PUBLISHED: "#c792ea",
  NEWS_PUBLISHED:       "#69db7c",
};
const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등", PRICE_DROP: "급락", VOLUME_SURGE: "거래량 급등",
  DISCLOSURE_PUBLISHED: "공시", NEWS_PUBLISHED: "뉴스",
};

export default function StockDetailScreen() {
  const { symbol } = useLocalSearchParams<{ symbol: string }>();
  const router = useRouter();
  const [stockId, setStockId]   = useState<number | null>(null);
  const [stockName, setName]    = useState("");
  const [price, setPrice]       = useState<StockPrice | null>(null);
  const [events, setEvents]     = useState<StockEvent[]>([]);
  const [news, setNews]         = useState<NewsItem[]>([]);
  const [loading, setLoading]   = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [tab, setTab]           = useState<"events"|"news">("events");

  useEffect(() => {
    if (!symbol) return;
    fetch(`${getApiBase()}/api/stocks/search?query=${symbol}`)
      .then(r => r.json())
      .then((stocks: { id: number; symbol: string; name: string }[]) => {
        const match = stocks.find(s => s.symbol === symbol);
        if (match) { setStockId(match.id); setName(match.name); }
      });
  }, [symbol]);

  const loadAll = useCallback(async (isRefresh = false) => {
    if (!stockId) return;
    if (isRefresh) setRefreshing(true);
    try {
      const [priceRes, eventsRes, newsRes] = await Promise.all([
        fetch(`${getApiBase()}/api/stocks/${stockId}/price`),
        fetch(`${getApiBase()}/api/stocks/${stockId}/events`),
        fetch(`${getApiBase()}/api/stocks/${stockId}/news?limit=20`),
      ]);
      if (priceRes.ok)  { const d = await priceRes.json();  if (d.hasData) setPrice(d); }
      if (eventsRes.ok) setEvents(await eventsRes.json());
      if (newsRes.ok)   setNews(await newsRes.json());
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [stockId]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // 3초마다 가격 갱신
  useEffect(() => {
    if (!stockId) return;
    const id = setInterval(() => {
      fetch(`${getApiBase()}/api/stocks/${stockId}/price`)
        .then(r => r.ok ? r.json() : null)
        .then(d => { if (d?.hasData) setPrice(d); });
    }, 3000);
    return () => clearInterval(id);
  }, [stockId]);

  if (loading) return <View style={s.center}><ActivityIndicator size="large" color={ACCENT} /></View>;

  const positive = (price?.changeRate ?? 0) >= 0;

  return (
    <View style={s.container}>
      <ScrollView
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => loadAll(true)} tintColor={ACCENT} />}
      >
        {/* 헤더 */}
        <View style={s.header}>
          <Text style={s.name}>{stockName || symbol}</Text>
          <Text style={s.sym}>{symbol}</Text>
          {price ? (
            <View style={s.priceRow}>
              <Text style={s.price}>₩{price.price.toLocaleString()}</Text>
              {price.changeRate != null && (
                <Text style={[s.changeRate, positive ? s.pos : s.neg]}>
                  {positive ? "+" : ""}{price.changeRate.toFixed(2)}%
                </Text>
              )}
            </View>
          ) : (
            <Text style={s.noPrice}>시세 정보 없음</Text>
          )}
          {price && <Text style={s.volume}>거래량 {price.volume.toLocaleString()}</Text>}
        </View>

        {/* 알림 설정 버튼 */}
        <TouchableOpacity style={s.alertBtn} onPress={() => router.push(`/`)}>
          <Text style={s.alertBtnText}>🔔 알림 설정</Text>
        </TouchableOpacity>

        {/* 탭 */}
        <View style={s.tabs}>
          {(["events", "news"] as const).map(t => (
            <TouchableOpacity key={t} style={[s.tab, tab === t && s.tabActive]} onPress={() => setTab(t)}>
              <Text style={[s.tabText, tab === t && s.tabTextActive]}>
                {t === "events" ? "이벤트" : "뉴스"}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {tab === "events" ? (
          <View style={s.section}>
            {events.length === 0
              ? <View style={s.emptySection}><Text style={s.empty}>최근 이벤트 없음</Text></View>
              : events.map(e => (
                <View key={e.id} style={[s.event, { borderLeftColor: EVENT_COLOR[e.eventType] ?? MUTED }]}>
                  <View style={s.eventRow}>
                    <View style={[s.eventTag, { backgroundColor: (EVENT_COLOR[e.eventType] ?? MUTED) + "22" }]}>
                      <Text style={[s.eventTagText, { color: EVENT_COLOR[e.eventType] ?? MUTED }]}>
                        {EVENT_LABEL[e.eventType] ?? e.eventType}
                      </Text>
                    </View>
                    <Text style={s.score}>{e.importanceScore}</Text>
                  </View>
                  <Text style={s.eventTitle} numberOfLines={2}>{e.title}</Text>
                  <Text style={s.eventTime}>
                    {new Date(e.eventTime).toLocaleString("ko-KR", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}
                  </Text>
                </View>
              ))
            }
          </View>
        ) : (
          <View style={s.section}>
            {news.length === 0
              ? <View style={s.emptySection}><Text style={s.empty}>최근 뉴스 없음</Text></View>
              : news.map(n => (
                <View key={n.id} style={s.newsItem}>
                  <View style={s.sentimentBadge}>
                    <Text style={[s.sentiment,
                      n.sentiment === "POSITIVE" ? s.pos : n.sentiment === "NEGATIVE" ? s.neg : { color: MUTED }
                    ]}>
                      {n.sentiment === "POSITIVE" ? "긍정" : n.sentiment === "NEGATIVE" ? "부정" : "중립"}
                    </Text>
                  </View>
                  <Text style={s.newsTitle} numberOfLines={2}>{n.title}</Text>
                  <Text style={s.newsSource}>{n.source} · {new Date(n.publishedAt).toLocaleDateString("ko-KR")}</Text>
                </View>
              ))
            }
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const BG = "#0d1117", SURF = "#161929", BORDER = "#242840", TEXT = "#e8eaf6", MUTED = "#5c6288", ACCENT = "#5c7cfa";

const s = StyleSheet.create({
  container:    { flex: 1, backgroundColor: BG },
  center:       { flex: 1, alignItems: "center", justifyContent: "center" },
  header:       { padding: 20, borderBottomWidth: 1, borderBottomColor: BORDER },
  name:         { fontSize: 20, fontWeight: "700", color: TEXT },
  sym:          { fontSize: 13, color: MUTED, marginBottom: 12 },
  priceRow:     { flexDirection: "row", alignItems: "baseline", gap: 10 },
  price:        { fontSize: 32, fontWeight: "800", color: TEXT },
  changeRate:   { fontSize: 16, fontWeight: "600" },
  noPrice:      { fontSize: 14, color: MUTED },
  volume:       { fontSize: 12, color: MUTED, marginTop: 6 },
  pos:          { color: "#ff5370" },
  neg:          { color: "#4fc3f7" },
  alertBtn:     { margin: 16, backgroundColor: SURF, borderWidth: 1, borderColor: BORDER, borderRadius: 8, padding: 12, alignItems: "center" },
  alertBtnText: { color: ACCENT, fontWeight: "600", fontSize: 14 },
  tabs:         { flexDirection: "row", borderBottomWidth: 1, borderBottomColor: BORDER, marginHorizontal: 16 },
  tab:          { paddingHorizontal: 16, paddingVertical: 10, borderBottomWidth: 2, borderBottomColor: "transparent" },
  tabActive:    { borderBottomColor: ACCENT },
  tabText:      { color: MUTED, fontWeight: "600", fontSize: 14 },
  tabTextActive:{ color: TEXT },
  section:      { padding: 16 },
  emptySection: { paddingVertical: 40, alignItems: "center" },
  empty:        { color: MUTED, fontSize: 14 },
  event:        { borderLeftWidth: 3, paddingLeft: 12, paddingVertical: 10, marginBottom: 10, backgroundColor: SURF, borderRadius: 6 },
  eventRow:     { flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginBottom: 6 },
  eventTag:     { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  eventTagText: { fontSize: 10, fontWeight: "700" },
  score:        { color: MUTED, fontSize: 12, fontWeight: "700" },
  eventTitle:   { fontSize: 13, color: TEXT, lineHeight: 18 },
  eventTime:    { fontSize: 11, color: MUTED, marginTop: 4 },
  newsItem:     { backgroundColor: SURF, borderRadius: 6, padding: 12, marginBottom: 8 },
  sentimentBadge:{ marginBottom: 6 },
  sentiment:    { fontSize: 11, fontWeight: "700" },
  newsTitle:    { fontSize: 13, color: TEXT, lineHeight: 18, marginBottom: 6 },
  newsSource:   { fontSize: 11, color: MUTED },
});
