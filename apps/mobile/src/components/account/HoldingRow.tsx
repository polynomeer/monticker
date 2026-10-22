import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, tabular } from "@/theme/type";
import { Card } from "@/components/ui";
import { formatPct, formatSignedWon, formatWon } from "@/lib/format";

/** 보유 한 줄 카드 — 이름·수량·평균가, 평가액·손익 */
export function HoldingRow({ symbol, name, quantity, avgPrice, value, pnl, pnlRate }: {
  symbol: string; name: string; quantity: number; avgPrice: number; value: number; pnl: number; pnlRate: number;
}) {
  const { c, market } = useTheme();
  const router = useRouter();
  return (
    <Card onPress={() => router.push(`/stocks/${symbol}`)} style={s.row} accessibilityLabel={`${name} ${quantity}주, 평가 ${formatWon(value)}원, 손익 ${formatPct(pnlRate)}`}>
      <View style={[s.avatar, { backgroundColor: c.raised }]}><Text style={[font.subhead, s.semibold, { color: c.text }]}>{name.slice(0, 1)}</Text></View>
      <View style={s.main}>
        <Text style={[font.headline, { color: c.text }]} numberOfLines={1}>{name}</Text>
        <Text style={[font.footnote, tabular, { color: c.muted }]}>{formatWon(quantity)}주 · 평균 {formatWon(avgPrice)}</Text>
      </View>
      <View style={s.right}>
        <Text style={[font.headline, tabular, { color: c.text }]}>{formatWon(value)}</Text>
        <Text style={[font.footnote, tabular, s.semibold, { color: changeColor(pnl, market, c.muted) }]}>
          {formatSignedWon(pnl)} ({formatPct(pnlRate)})
        </Text>
      </View>
    </Card>
  );
}

const s = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 14 },
  avatar: { width: 40, height: 40, borderRadius: 20, alignItems: "center", justifyContent: "center" },
  semibold: { fontWeight: "600" },
  main: { flex: 1, minWidth: 0, gap: 1 },
  right: { alignItems: "flex-end", gap: 1 },
});
