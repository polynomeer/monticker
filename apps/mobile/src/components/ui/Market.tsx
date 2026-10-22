import { StyleSheet, Text, View } from "react-native";
import Svg, { Circle, Polyline } from "react-native-svg";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, tabular } from "@/theme/type";
import { formatPct, sparklinePoints } from "@/lib/format";

/** 등락률 글자(색 + 부호) */
export function ChangeText({ value, size = "subhead", suffix }: { value: number; size?: "subhead" | "footnote" | "body"; suffix?: string }) {
  const { c, market } = useTheme();
  return (
    <Text style={[font[size], tabular, s.bold, { color: changeColor(value, market, c.muted) }]}>
      {formatPct(value)}{suffix}
    </Text>
  );
}

/** 상승·하락 색으로 칠한 등락률 알약(관심종목 오른쪽) */
export function ChangePill({ value }: { value: number }) {
  const { c, market } = useTheme();
  const bg = changeColor(value, market, c.fill);
  const fg = value > 0 ? market.onUp : value < 0 ? market.onDown : c.text;
  return (
    <View style={[s.pill, { backgroundColor: bg }]}>
      <Text style={[font.footnote, tabular, s.bold, { color: fg }]}>{formatPct(value)}</Text>
    </View>
  );
}

export type BadgeTone = "up" | "down" | "accent" | "caution" | "live" | "disclosure" | "muted";

/** 작은 배지(급등·급락·거래량·모의·실전) — 옅은 바탕 + 진한 글자 */
export function Badge({ label, tone, dot }: { label: string; tone: BadgeTone; dot?: boolean }) {
  const { c, market } = useTheme();
  const color = { up: market.up, down: market.down, accent: c.accent, caution: c.caution, live: c.live, disclosure: c.disclosure, muted: c.muted }[tone];
  const bg = tone === "caution" ? c.cautionBg : tone === "live" ? c.liveBg : withAlpha(color, 0.16);
  return (
    <View style={[s.badge, { backgroundColor: bg }]}>
      {dot && <View style={[s.dot, { backgroundColor: color }]} />}
      <Text style={[font.caption, s.heavy, { color }]}>{label}</Text>
    </View>
  );
}

/** 작은 선 그래프. 마지막 점 강조는 선택 */
export function Sparkline({ values, width = 60, height = 28, color, strokeWidth = 2, marker }: {
  values: number[]; width?: number; height?: number; color?: string; strokeWidth?: number; marker?: { index: number; color: string };
}) {
  const { c, market } = useTheme();
  if (values.length < 2) return <View style={{ width, height }} />;
  const stroke = color ?? changeColor(values[values.length - 1] - values[0], market, c.muted);
  const pts = sparklinePoints(values, width, height, strokeWidth + 2);
  const m = marker ? pts[Math.min(marker.index, pts.length - 1)] : null;
  return (
    <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <Polyline points={pts.map(([x, y]) => `${x},${y}`).join(" ")} fill="none" stroke={stroke} strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />
      {m && marker && <Circle cx={m[0]} cy={m[1]} r={5} fill={marker.color} />}
    </Svg>
  );
}

/** #rrggbb → rgba. 이미 rgba면 그대로 */
export function withAlpha(hex: string, alpha: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

const s = StyleSheet.create({
  bold: { fontWeight: "600" },
  heavy: { fontWeight: "700" },
  pill: { minWidth: 64, paddingVertical: 2, paddingHorizontal: 6, borderRadius: 8, alignItems: "center" },
  badge: { flexDirection: "row", alignItems: "center", gap: 5, paddingHorizontal: 8, paddingVertical: 2, borderRadius: 9, alignSelf: "flex-start" },
  dot: { width: 6, height: 6, borderRadius: 3 },
});
