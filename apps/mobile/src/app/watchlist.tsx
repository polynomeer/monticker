import { useEffect, useState } from "react";
import { View, Text, FlatList, StyleSheet, ActivityIndicator, TouchableOpacity, Alert } from "react-native";
import { useRouter } from "expo-router";
import { getApiBase } from "@/services/api";

interface WatchlistGroup {
  id: number;
  name: string;
  items: WatchlistItem[];
}

interface WatchlistItem {
  id: number;
  stockId: number;
  symbol: string;
  name: string;
}

export default function WatchlistScreen() {
  const [groups, setGroups] = useState<WatchlistGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    fetchWatchlist();
  }, []);

  const fetchWatchlist = async () => {
    try {
      const res = await fetch(`${getApiBase()}/api/watchlists`);
      if (res.ok) {
        const data = await res.json();
        setGroups(data);
      }
    } catch {
      Alert.alert("오류", "관심종목을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#2563eb" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {groups.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.empty}>관심종목이 없습니다.</Text>
        </View>
      ) : (
        <FlatList
          data={groups}
          keyExtractor={(g) => String(g.id)}
          renderItem={({ item: group }) => (
            <View style={styles.group}>
              <Text style={styles.groupName}>{group.name}</Text>
              {group.items.map((item) => (
                <TouchableOpacity
                  key={item.id}
                  style={styles.item}
                  onPress={() => router.push(`/stocks/${item.symbol}`)}
                >
                  <Text style={styles.symbol}>{item.symbol}</Text>
                  <Text style={styles.name}>{item.name}</Text>
                </TouchableOpacity>
              ))}
            </View>
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container:  { flex: 1, backgroundColor: "#fff" },
  center:     { flex: 1, alignItems: "center", justifyContent: "center" },
  empty:      { color: "#6b7280", fontSize: 14 },
  group:      { paddingHorizontal: 16, paddingTop: 16 },
  groupName:  { fontSize: 13, fontWeight: "600", color: "#6b7280", marginBottom: 8, textTransform: "uppercase" },
  item:       { paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: "#f3f4f6", flexDirection: "row", alignItems: "center", gap: 12 },
  symbol:     { fontSize: 15, fontWeight: "700", color: "#1f2937", width: 80 },
  name:       { fontSize: 14, color: "#374151", flex: 1 },
});
