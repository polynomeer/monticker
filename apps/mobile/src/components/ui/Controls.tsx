import type { ReactNode } from "react";
import { ActivityIndicator, Platform, Pressable, StyleSheet, Switch as RNSwitch, Text, View, type StyleProp, type ViewStyle } from "react-native";
import { BlurView } from "expo-blur";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius } from "@/theme/type";

/** 떠 있는 유리 원형 버튼(44pt) — 위 버튼 줄의 프로필·뒤로·정렬 */
export function GlassButton({ children, onPress, accessibilityLabel, style }: {
  children: ReactNode; onPress: () => void; accessibilityLabel: string; style?: StyleProp<ViewStyle>;
}) {
  return (
    <Glass style={[s.glassButton, style]}>
      <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={accessibilityLabel} style={s.glassHit} hitSlop={4}>
        {children}
      </Pressable>
    </Glass>
  );
}

/** 블러 바탕 + 얇은 테두리. 블러가 없는 환경(웹 확인용)은 반투명 색만 */
export function Glass({ children, style, borderColor }: { children: ReactNode; style?: StyleProp<ViewStyle>; borderColor?: string }) {
  const { c, scheme } = useTheme();
  return (
    <View style={[s.glass, { borderColor: borderColor ?? c.glassBorder, shadowColor: c.shadow }, style]}>
      {Platform.OS === "ios" && <BlurView intensity={40} tint={scheme === "dark" ? "dark" : "light"} style={StyleSheet.absoluteFill} />}
      <View style={[StyleSheet.absoluteFill, { backgroundColor: c.glass }]} />
      {children}
    </View>
  );
}

/** 알약 칩 — 선택이면 강조색 바탕 */
export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress?: () => void }) {
  const { c } = useTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected: !!selected }}
      style={[s.chip, { backgroundColor: selected ? c.accent : c.fill }]}
    >
      <Text style={[font.subhead, { color: selected ? c.onAccent : c.body, fontWeight: selected ? "600" : "500" }]}>{label}</Text>
    </Pressable>
  );
}

/** 분할 버튼(화면 모드·내 계좌 구역) */
export function Segmented<T extends string>({ options, value, onChange, accessibilityLabel, compact }: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
  accessibilityLabel: string;
  compact?: boolean;
}) {
  const { c, scheme } = useTheme();
  return (
    <View accessibilityRole="tablist" accessibilityLabel={accessibilityLabel} style={[s.segment, { backgroundColor: scheme === "dark" ? c.card : c.switchOff }]}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <Pressable
            key={o.value}
            onPress={() => onChange(o.value)}
            accessibilityRole="tab"
            accessibilityState={{ selected: on }}
            style={[s.segmentItem, compact && s.segmentCompact, on && { backgroundColor: c.segmentOn, shadowColor: c.shadow, ...s.segmentShadow }]}
          >
            <Text style={[compact ? font.footnote : font.subhead, { color: on ? c.text : c.soft, fontWeight: on ? "600" : "500" }]}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Switch({ value, onValueChange, accessibilityLabel, disabled }: {
  value: boolean; onValueChange: (v: boolean) => void; accessibilityLabel: string; disabled?: boolean;
}) {
  const { c } = useTheme();
  return (
    <RNSwitch
      value={value}
      onValueChange={onValueChange}
      disabled={disabled}
      accessibilityLabel={accessibilityLabel}
      trackColor={{ true: c.switchOn, false: c.switchOff }}
      thumbColor="#ffffff"
      ios_backgroundColor={c.switchOff}
    />
  );
}

type ButtonTone = "accent" | "up" | "down" | "live" | "plain";

/** 큰 버튼(높이 50). 매수·매도는 상승·하락 색, 실전 확인은 live */
export function Button({ label, onPress, tone = "accent", disabled, busy, style, accessibilityHint }: {
  label: string; onPress: () => void; tone?: ButtonTone; disabled?: boolean; busy?: boolean;
  style?: StyleProp<ViewStyle>; accessibilityHint?: string;
}) {
  const { c, market } = useTheme();
  const bg = { accent: c.accent, up: market.up, down: market.down, live: c.live, plain: c.fill }[tone];
  const fg = { accent: c.onAccent, up: market.onUp, down: market.onDown, live: c.onAccent, plain: c.text }[tone];
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || busy}
      accessibilityRole="button"
      accessibilityState={{ disabled: !!(disabled || busy), busy: !!busy }}
      accessibilityHint={accessibilityHint}
      style={({ pressed }) => [s.button, { backgroundColor: bg, opacity: disabled ? 0.4 : pressed ? 0.85 : 1 }, style]}
    >
      {busy ? <ActivityIndicator color={fg} /> : <Text style={[font.callout, { color: fg }]}>{label}</Text>}
    </Pressable>
  );
}

const s = StyleSheet.create({
  glass: { borderWidth: 1, overflow: "hidden", shadowOffset: { width: 0, height: 6 }, shadowOpacity: 0.3, shadowRadius: 9, elevation: 4 },
  glassButton: { width: 44, height: 44, borderRadius: 22 },
  glassHit: { flex: 1, alignItems: "center", justifyContent: "center" },
  chip: { height: 36, paddingHorizontal: 16, borderRadius: radius.chip, alignItems: "center", justifyContent: "center" },
  segment: { flexDirection: "row", padding: 3, borderRadius: 20 },
  segmentItem: { flex: 1, height: 34, borderRadius: 17, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 },
  segmentCompact: { height: 30, borderRadius: 15 },
  segmentShadow: { shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.3, shadowRadius: 4, elevation: 2 },
  button: { height: 50, borderRadius: 25, alignItems: "center", justifyContent: "center", paddingHorizontal: 20 },
});
