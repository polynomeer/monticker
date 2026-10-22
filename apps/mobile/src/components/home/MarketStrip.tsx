import { ScrollView, StyleSheet, Text, View } from "react-native";
import type { MarketIndex, MarketStatus } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius, tabular } from "@/theme/type";
import { useApi } from "@/services/useApi";
import { changeColor } from "@/theme/palette";
import { formatPct } from "@/lib/format";

const SLOTS = [
  { code: "KOSPI", label: "KOSPI" },
  { code: "KOSDAQ", label: "KOSDAQ" },
  { code: "USDKRW", label: "USD" },
];

function sessionLabel(s: MarketStatus | null): { text: string; open: boolean } {
  if (!s) return { text: "장 상태 확인 중", open: false };
  if (!s.isTradingDay) return { text: s.holidayName ? `휴장 · ${s.holidayName}` : "휴장", open: false };
  switch (s.status) {
    case "OPEN": return { text: "정규장", open: true };
    case "PRE": return { text: "장 시작 전", open: false };
    case "POST": return { text: "장 마감", open: false };
    default: return { text: "장 마감", open: false };
  }
}

/** 홈 시장 요약 한 줄 — 장 상태 · KOSPI · KOSDAQ · USD/KRW 등락률 */
export function MarketStrip() {
  const { c, market } = useTheme();
  const status = useApi<MarketStatus>("/api/market/status", { refreshMs: 60_000 });
  const indices = useApi<MarketIndex[]>("/api/market/indices", { refreshMs: 30_000 });
  const session = sessionLabel(status.data);
  const byCode = new Map((indices.data ?? []).map((i) => [i.code, i]));
  const mocked = (indices.data ?? []).some((i) => i.isMocked);

  return (
    // 좁은 화면·큰 글자에서도 숫자를 자르지 않게 가로로 민다
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      accessibilityRole="summary"
      accessibilityLabel={`시장 요약${mocked ? " (지수·환율은 모의 값)" : ""}`}
      style={[s.frame, { backgroundColor: c.card }]}
      contentContainerStyle={s.strip}
    >
      <View style={s.item}>
        <View style={[s.dot, { backgroundColor: session.open ? c.positive : c.muted }]} />
        <Text style={[font.footnote, { color: c.soft }]}>{session.text}</Text>
      </View>
      {SLOTS.map(({ code, label }) => {
        const rate = byCode.get(code)?.changeRate;
        return (
          <Text key={code} style={[font.footnote, { color: c.text }]}>
            {label}{" "}
            <Text style={[tabular, s.bold, { color: rate == null ? c.muted : changeColor(rate, market, c.muted) }]}>
              {rate == null ? "—" : formatPct(rate)}
            </Text>
          </Text>
        );
      })}
      {mocked && <Text style={[font.caption, { color: c.caution }]}>모의</Text>}
    </ScrollView>
  );
}

const s = StyleSheet.create({
  frame: { borderRadius: radius.tile, flexGrow: 0 },
  strip: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 10, paddingHorizontal: 14 },
  item: { flexDirection: "row", alignItems: "center", gap: 5 },
  dot: { width: 7, height: 7, borderRadius: 4 },
  bold: { fontWeight: "600" },
});
