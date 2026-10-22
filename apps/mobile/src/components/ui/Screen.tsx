import type { ReactNode } from "react";
import { RefreshControl, ScrollView, StyleSheet, Text, View, type ViewStyle } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";

/** 하단 떠 있는 탭 바(상태 바 포함)에 가리지 않게 남기는 여백 */
export const TAB_BAR_SPACE = 172;

interface Props {
  title?: string;
  subtitle?: string;
  /** 오른쪽 위 유리 버튼들 */
  actions?: ReactNode;
  /** 왼쪽 위(뒤로 등) */
  leading?: ReactNode;
  children: ReactNode;
  refreshing?: boolean;
  onRefresh?: () => void;
  /** 하단 여백 — 탭 바가 없는 화면은 작게 */
  bottomSpace?: number;
  scroll?: boolean;
  contentStyle?: ViewStyle;
}

/** 시안의 화면 틀: 안전 영역 + 위 버튼 줄 + 큰 제목 + 16pt 여백의 세로 목록 */
export function Screen({
  title, subtitle, actions, leading, children, refreshing, onRefresh, bottomSpace = TAB_BAR_SPACE, scroll = true, contentStyle,
}: Props) {
  const { c } = useTheme();
  const insets = useSafeAreaInsets();
  const header = (
    <>
      {(actions || leading) && (
        <View style={s.toolbar}>
          <View style={s.leading}>{leading}</View>
          <View style={s.actions}>{actions}</View>
        </View>
      )}
      {title && (
        <View style={s.titleBlock}>
          <Text style={[font.largeTitle, { color: c.text }]} accessibilityRole="header">{title}</Text>
          {subtitle && <Text style={[font.subhead, { color: c.muted }]}>{subtitle}</Text>}
        </View>
      )}
    </>
  );
  const padding = { paddingTop: insets.top + 6, paddingBottom: bottomSpace + insets.bottom };

  if (!scroll) {
    return <View style={[s.fill, { backgroundColor: c.bg }, s.content, padding, contentStyle]}>{header}{children}</View>;
  }
  return (
    <ScrollView
      style={[s.fill, { backgroundColor: c.bg }]}
      contentContainerStyle={[s.content, padding, contentStyle]}
      refreshControl={onRefresh ? <RefreshControl refreshing={!!refreshing} onRefresh={onRefresh} tintColor={c.accent} /> : undefined}
    >
      {header}
      {children}
    </ScrollView>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  content: { paddingHorizontal: 16, gap: 14 },
  toolbar: { flexDirection: "row", alignItems: "center", minHeight: 44 },
  leading: { flex: 1, flexDirection: "row" },
  actions: { flexDirection: "row", gap: 10 },
  titleBlock: { gap: 2, marginTop: -6 },
});
