import { View, Text, TouchableOpacity, StyleSheet } from "react-native";
import { useRouter } from "expo-router";

/** 로그인이 필요한 화면의 자리 — 이유 한 줄과 로그인 버튼 */
export function SignInRequired({ message }: { message: string }) {
  const router = useRouter();
  return (
    <View style={s.center}>
      <Text style={s.icon}>🔒</Text>
      <Text style={s.title}>로그인이 필요해요</Text>
      <Text style={s.sub}>{message}</Text>
      <TouchableOpacity style={s.cta} onPress={() => router.push("/login")} accessibilityRole="button">
        <Text style={s.ctaText}>로그인</Text>
      </TouchableOpacity>
    </View>
  );
}

const s = StyleSheet.create({
  center:  { flex: 1, alignItems: "center", justifyContent: "center", padding: 32, backgroundColor: "#0d1117" },
  icon:    { fontSize: 40, marginBottom: 12 },
  title:   { color: "#e8eaf6", fontSize: 17, fontWeight: "700", marginBottom: 6 },
  sub:     { color: "#5c6288", fontSize: 13, textAlign: "center", marginBottom: 24 },
  cta:     { backgroundColor: "#5c7cfa", paddingHorizontal: 24, paddingVertical: 12, borderRadius: 24 },
  ctaText: { color: "#fff", fontWeight: "700", fontSize: 14 },
});
