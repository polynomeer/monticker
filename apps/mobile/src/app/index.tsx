import { View, Text, StyleSheet, TouchableOpacity } from "react-native";
import { useRouter } from "expo-router";

export default function HomeScreen() {
  const router = useRouter();

  return (
    <View style={styles.container}>
      <Text style={styles.title}>monticker</Text>
      <Text style={styles.subtitle}>이벤트 중심 주식 관찰</Text>

      <TouchableOpacity style={styles.button} onPress={() => router.push("/watchlist")}>
        <Text style={styles.buttonText}>관심종목 보기</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: "#fff", padding: 24 },
  title:     { fontSize: 28, fontWeight: "700", color: "#1f2937", marginBottom: 8 },
  subtitle:  { fontSize: 14, color: "#6b7280", marginBottom: 48 },
  button:    { backgroundColor: "#2563eb", paddingHorizontal: 24, paddingVertical: 12, borderRadius: 8 },
  buttonText:{ color: "#fff", fontWeight: "600", fontSize: 16 },
});
