import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { brokerName, useTradingMode } from "@/services/mode";
import { shouldShowStatus, useStatusSummary } from "@/services/status";
import { Glass, Icon } from "@/components/ui";

/**
 * 탭 바 위 상태 바(ADR-113) — 모드 배지 + 진행 중인 것. 누르면 내 계좌.
 * 모의는 진행 중인 것이 있을 때만, 실전은 늘(주황 테두리).
 */
export function StatusPill() {
  const { c } = useTheme();
  const router = useRouter();
  const { mode, broker } = useTradingMode();
  const summary = useStatusSummary();
  if (!shouldShowStatus(mode, summary)) return null;

  const live = mode === "live";
  const items: string[] = [];
  if (live) items.push(`${brokerName(broker?.provider).replace("증권", "")} 연동`);
  if (summary?.runningStrategies) items.push(`전략 ${summary.runningStrategies} 실행 중`);
  if (summary?.openOrders) items.push(`미체결 ${summary.openOrders}`);
  const idle = live && !summary?.runningStrategies && !summary?.openOrders;

  const label = [
    live ? `실전 모드: ${brokerName(broker?.provider)} 연동됨` : "진행 중: 모의투자 모드",
    summary?.runningStrategies ? `실행 중 전략 ${summary.runningStrategies}개` : null,
    summary?.openOrders ? `미체결 주문 ${summary.openOrders}건` : null,
    idle ? "진행 중인 전략과 주문 없음" : null,
    "내 계좌로 이동",
  ].filter(Boolean).join(", ");

  return (
    <Glass style={s.pill} borderColor={live ? c.liveBorder : undefined}>
      <Pressable onPress={() => router.navigate("/account")} accessibilityRole="button" accessibilityLabel={label} style={s.row}>
        <View style={[s.chip, { backgroundColor: live ? c.liveBg : c.cautionBg }]}>
          <View style={[s.chipDot, { backgroundColor: live ? c.live : c.caution }]} />
          <Text style={[font.footnote, s.bold, { color: live ? c.live : c.caution }]}>{live ? "실전" : "모의"}</Text>
        </View>
        {items.map((t, i) => (
          <View key={t} style={s.item}>
            {i === (live ? 1 : 0) && summary?.runningStrategies ? <View style={[s.runDot, { backgroundColor: c.positive }]} /> : null}
            <Text style={[font.footnote, { color: c.body }]} numberOfLines={1}>{t}</Text>
          </View>
        ))}
        {idle && <Text style={[font.footnote, { color: c.muted }]}>진행 중인 것 없음</Text>}
        <View style={s.chevron}><Icon name="chevronRight" size={14} color={c.muted} strokeWidth={2.6} /></View>
      </Pressable>
    </Glass>
  );
}

const s = StyleSheet.create({
  pill: { height: 44, borderRadius: 22 },
  row: { flex: 1, flexDirection: "row", alignItems: "center", gap: 10, paddingHorizontal: 14 },
  chip: { flexDirection: "row", alignItems: "center", gap: 5, paddingHorizontal: 8, paddingVertical: 2, borderRadius: 9 },
  chipDot: { width: 6, height: 6, borderRadius: 3 },
  bold: { fontWeight: "700" },
  item: { flexDirection: "row", alignItems: "center", gap: 5, flexShrink: 1 },
  runDot: { width: 7, height: 7, borderRadius: 4 },
  chevron: { marginLeft: "auto" },
});
