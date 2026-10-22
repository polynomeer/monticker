import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { Button } from "./Controls";

export function Loading() {
  const { c } = useTheme();
  return <View style={[s.center, { backgroundColor: c.bg }]}><ActivityIndicator size="large" color={c.accent} /></View>;
}

/** 빈 상태·오류 — 제목 한 줄, 설명, 버튼 하나 */
export function EmptyState({ title, sub, action, fill = true }: {
  title: string; sub?: string; action?: { label: string; onPress: () => void }; fill?: boolean;
}) {
  const { c } = useTheme();
  return (
    <View style={[fill ? s.center : s.inline, fill && { backgroundColor: c.bg }]}>
      <Text style={[font.headline, s.text, { color: c.text }]}>{title}</Text>
      {sub && <Text style={[font.subhead, s.text, { color: c.muted }]}>{sub}</Text>}
      {action && <Button label={action.label} onPress={action.onPress} style={s.action} />}
    </View>
  );
}

const s = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: 32, gap: 8 },
  inline: { alignItems: "center", paddingVertical: 32, paddingHorizontal: 16, gap: 8 },
  text: { textAlign: "center", lineHeight: 22 },
  action: { marginTop: 12, alignSelf: "center" },
});
