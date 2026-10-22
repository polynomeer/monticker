import { StyleSheet, Text, View } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, radius, tabular } from "@/theme/type";
import { allocation } from "@/lib/account";
import { formatPct, formatSignedWon, formatWon } from "@/lib/format";

export interface AssetPart { label: string; value: number; color: string }

/** 내 계좌 총자산 카드 — 총액, 오늘 손익, 현금·예약금·평가액 막대와 범례 */
export function AssetSummary({ label, total, today, todayPct, parts }: {
  label: string; total: number; today: number | null; todayPct: number | null; parts: AssetPart[];
}) {
  const { c, market } = useTheme();
  const widths = allocation(parts.map((p) => p.value));
  const shares = parts.map((p) => (total > 0 ? ((p.value / total) * 100).toFixed(1) : "0"));
  return (
    <View style={[s.card, { backgroundColor: c.card }]} accessibilityLabel={`${label} ${formatWon(total)}원`}>
      <View style={s.head}>
        <Text style={[font.footnote, { color: c.muted }]}>{label}</Text>
        <Text style={[s.total, tabular, { color: c.text }]}>
          {formatWon(total)}<Text style={[s.unit, { color: c.soft }]}>원</Text>
        </Text>
        {today != null && (
          <Text style={[font.subhead, tabular, s.semibold, { color: changeColor(today, market, c.muted) }]}>
            오늘 {formatSignedWon(today)}{todayPct != null ? ` (${formatPct(todayPct)})` : ""}
          </Text>
        )}
      </View>
      <View
        style={s.bar}
        accessibilityRole="image"
        accessibilityLabel={parts.map((p, i) => `${p.label} ${shares[i]}%`).join(", ")}
      >
        {parts.map((p, i) => widths[i] > 0 && <View key={p.label} style={[s.segment, { flexGrow: widths[i], backgroundColor: p.color }]} />)}
      </View>
      <View style={s.legend}>
        {parts.map((p) => (
          <View key={p.label} style={s.legendItem}>
            <View style={s.legendHead}>
              <View style={[s.dot, { backgroundColor: p.color }]} />
              <Text style={[font.caption, { color: c.muted }]}>{p.label}</Text>
            </View>
            <Text style={[font.subhead, tabular, s.semibold, { color: c.text }]}>{formatWon(p.value)}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  card: { borderRadius: radius.card, padding: 18, gap: 12 },
  head: { gap: 2 },
  total: { fontSize: 32, fontWeight: "700", letterSpacing: -0.5 },
  unit: { fontSize: 20, fontWeight: "600" },
  semibold: { fontWeight: "600" },
  bar: { flexDirection: "row", gap: 3, height: 8 },
  segment: { borderRadius: 4, flexBasis: 0 },
  legend: { flexDirection: "row", gap: 8 },
  legendItem: { flex: 1, gap: 1 },
  legendHead: { flexDirection: "row", alignItems: "center", gap: 5 },
  dot: { width: 7, height: 7, borderRadius: 4 },
});
