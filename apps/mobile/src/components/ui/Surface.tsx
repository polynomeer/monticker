import { Children, Fragment, type ReactNode } from "react";
import { Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius } from "@/theme/type";
import { Icon } from "./Icon";

/** 둥근 카드(반경 26). onPress가 있으면 눌리는 카드 */
export function Card({ children, style, onPress, accessibilityLabel }: {
  children: ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void; accessibilityLabel?: string;
}) {
  const { c } = useTheme();
  const base = [s.card, { backgroundColor: c.card }, style];
  if (!onPress) return <View style={base}>{children}</View>;
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      style={({ pressed }) => [base, pressed && { backgroundColor: c.raised }]}
    >
      {children}
    </Pressable>
  );
}

/** 목록 위 작은 회색 제목(설정 화면 묶음 이름) */
export function SectionLabel({ children }: { children: string }) {
  const { c } = useTheme();
  return <Text style={[font.section, s.sectionLabel, { color: c.muted }]} accessibilityRole="header">{children}</Text>;
}

/** 큰 구역 제목 + 오른쪽 링크 */
export function SectionHeader({ title, sub, action }: { title: string; sub?: string; action?: ReactNode }) {
  const { c } = useTheme();
  return (
    <View style={s.sectionHeader}>
      <View style={s.flex}>
        <Text style={[font.title, { color: c.text }]} accessibilityRole="header">{title}</Text>
        {sub && <Text style={[font.footnote, { color: c.muted }]}>{sub}</Text>}
      </View>
      {action}
    </View>
  );
}

/** iOS 묶음 목록 — 항목 사이 들여쓴 구분선 */
export function GroupedList({ children, inset = 16 }: { children: ReactNode; inset?: number }) {
  const { c } = useTheme();
  const items = Children.toArray(children).filter(Boolean);
  return (
    <View style={[s.list, { backgroundColor: c.card }]}>
      {items.map((child, i) => (
        <Fragment key={i}>
          {i > 0 && <View style={[s.sep, { marginLeft: inset, backgroundColor: c.line }]} />}
          {child}
        </Fragment>
      ))}
    </View>
  );
}

interface RowProps {
  label: string;
  value?: string;
  /** 왼쪽 색 사각 아이콘 */
  icon?: ReactNode;
  trailing?: ReactNode;
  onPress?: () => void;
  chevron?: boolean;
  destructive?: boolean;
  accessibilityHint?: string;
}

export function ListRow({ label, value, icon, trailing, onPress, chevron = !!onPress, destructive, accessibilityHint }: RowProps) {
  const { c } = useTheme();
  const body = (
    <>
      {icon}
      <Text style={[font.body, s.flex, { color: destructive ? c.danger : c.text }]}>{label}</Text>
      {value != null && <Text style={[font.subhead, { color: c.muted }]}>{value}</Text>}
      {trailing}
      {chevron && <Icon name="chevronRight" size={12} color={c.chevron} strokeWidth={2.8} />}
    </>
  );
  if (!onPress) return <View style={s.row}>{body}</View>;
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityHint={accessibilityHint}
      style={({ pressed }) => [s.row, pressed && { backgroundColor: c.raised }]}
    >
      {body}
    </Pressable>
  );
}

/** 설정 목록 왼쪽 색 사각 아이콘 */
export function RowIcon({ children, color }: { children: ReactNode; color: string }) {
  return <View style={[s.rowIcon, { backgroundColor: color }]}>{children}</View>;
}

const s = StyleSheet.create({
  flex: { flex: 1 },
  card: { borderRadius: radius.card, padding: 16, overflow: "hidden" },
  sectionLabel: { paddingHorizontal: 16, marginTop: 4, marginBottom: -6 },
  sectionHeader: { flexDirection: "row", alignItems: "flex-end", paddingHorizontal: 4, gap: 8 },
  list: { borderRadius: radius.card, overflow: "hidden" },
  sep: { height: StyleSheet.hairlineWidth },
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingHorizontal: 16, minHeight: 50 },
  rowIcon: { width: 30, height: 30, borderRadius: 8, alignItems: "center", justifyContent: "center" },
});
