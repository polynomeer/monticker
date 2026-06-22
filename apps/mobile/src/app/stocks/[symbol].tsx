import { useEffect, useState } from "react";
import { View, Text, StyleSheet, ScrollView, ActivityIndicator } from "react-native";
import { useLocalSearchParams } from "expo-router";
import { getApiBase } from "@/services/api";

interface StockPrice {
  stockId: number;
  symbol: string;
  price: number;
  volume: number;
  timestamp: string;
  hasData: boolean;
}

interface StockEvent {
  id: number;
  eventType: string;
  title: string;
  description: string;
  importanceScore: number;
  eventTime: string;
}

const EVENT_COLOR: Record<string, string> = {
  PRICE_SPIKE:          "#16a34a",
  PRICE_DROP:           "#dc2626",
  VOLUME_SURGE:         "#2563eb",
  DISCLOSURE_PUBLISHED: "#7c3aed",
};

export default function StockDetailScreen() {
  const { symbol } = useLocalSearchParams<{ symbol: string }>();
  const [stockId, setStockId] = useState<number | null>(null);
  const [price, setPrice] = useState<StockPrice | null>(null);
  const [events, setEvents] = useState<StockEvent[]>([]);
  const [loading, setLoading] = useState(true);

  // Resolve stockId from symbol
  useEffect(() => {
    if (!symbol) return;
    fetch(`${getApiBase()}/api/stocks/search?query=${symbol}`)
      .then(r => r.json())
      .then((stocks: { id: number; symbol: string }[]) => {
        const match = stocks.find(s => s.symbol === symbol);
        if (match) setStockId(match.id);
      });
  }, [symbol]);

  // Fetch price
  useEffect(() => {
    if (!stockId) return;
    const fetchPrice = () =>
      fetch(`${getApiBase()}/api/stocks/${stockId}/price`)
        .then(r => r.json())
        .then(data => { if (data.hasData) setPrice(data); });

    fetchPrice();
    const id = setInterval(fetchPrice, 3000);
    return () => clearInterval(id);
  }, [stockId]);

  // Fetch events
  useEffect(() => {
    if (!stockId) return;
    const fetchEvents = () =>
      fetch(`${getApiBase()}/api/stocks/${stockId}/events`)
        .then(r => r.json())
        .then(data => { setEvents(data); setLoading(false); });

    fetchEvents();
    const id = setInterval(fetchEvents, 5000);
    return () => clearInterval(id);
  }, [stockId]);

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#2563eb" />
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      {/* 헤더 */}
      <View style={styles.header}>
        <Text style={styles.symbol}>{symbol}</Text>
        {price && (
          <Text style={styles.price}>
            {price.price.toLocaleString("ko-KR")}
          </Text>
        )}
        {price && (
          <Text style={styles.volume}>거래량 {price.volume.toLocaleString()}</Text>
        )}
      </View>

      {/* 이벤트 타임라인 */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>이벤트 타임라인</Text>
        {events.length === 0 ? (
          <Text style={styles.empty}>최근 이벤트 없음</Text>
        ) : (
          events.map(e => (
            <View key={e.id} style={[styles.event, { borderLeftColor: EVENT_COLOR[e.eventType] ?? "#6b7280" }]}>
              <View style={styles.eventRow}>
                <Text style={styles.eventTitle} numberOfLines={2}>{e.title}</Text>
                <Text style={styles.eventScore}>{e.importanceScore}</Text>
              </View>
              <Text style={styles.eventTime}>
                {new Date(e.eventTime).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}
              </Text>
            </View>
          ))
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container:    { flex: 1, backgroundColor: "#fff" },
  center:       { flex: 1, alignItems: "center", justifyContent: "center" },
  header:       { padding: 20, borderBottomWidth: 1, borderBottomColor: "#f3f4f6" },
  symbol:       { fontSize: 14, color: "#6b7280", marginBottom: 4 },
  price:        { fontSize: 32, fontWeight: "700", color: "#1f2937" },
  volume:       { fontSize: 12, color: "#9ca3af", marginTop: 4 },
  section:      { padding: 16 },
  sectionTitle: { fontSize: 15, fontWeight: "600", color: "#374151", marginBottom: 12 },
  empty:        { fontSize: 13, color: "#9ca3af", textAlign: "center", paddingVertical: 16 },
  event:        { borderLeftWidth: 3, paddingLeft: 12, paddingVertical: 10, marginBottom: 8, backgroundColor: "#f9fafb", borderRadius: 4 },
  eventRow:     { flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between" },
  eventTitle:   { fontSize: 13, color: "#374151", flex: 1, marginRight: 8 },
  eventScore:   { fontSize: 12, fontWeight: "700", color: "#6b7280" },
  eventTime:    { fontSize: 11, color: "#9ca3af", marginTop: 4 },
});
