import { Pressable, StyleSheet, Text, View } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { resolveMarketColors, type UpDownPreference } from "@/theme/palette";
import { ChangePill, GroupedList, Icon, Screen, Sparkline } from "@/components/ui";
import { BackButton } from "@/components/ui/BackButton";

const OPTIONS: { value: UpDownPreference; title: string; sub: (dark: boolean) => string }[] = [
  { value: "auto", title: "화면 모드에 맞춤 (기본)", sub: (dark) => (dark ? "다크는 초록 상승 · 라이트는 빨강 상승" : "라이트는 빨강 상승 · 다크는 초록 상승") },
  { value: "redUp", title: "빨강 상승 · 파랑 하락", sub: () => "국내 증권사 방식 · 두 모드 모두" },
  { value: "greenUp", title: "초록 상승 · 빨강 하락", sub: () => "해외 증권사·거래소 방식 · 두 모드 모두" },
];

const PREVIEW = [
  { name: "삼성전자", meta: "005930 · 거래량 3.2×", price: "71,255", change: 4.12, line: [22, 20, 23, 18, 19, 12, 9, 4].map((v) => 28 - v) },
  { name: "SK하이닉스", meta: "000660 · 거래량 2.1×", price: "182,400", change: -3.05, line: [6, 8, 7, 12, 11, 17, 20, 24].map((v) => 28 - v) },
];

/** 설정 › 상승·하락 색 — 미리보기 + 세 가지. 위험 경고·로그아웃 빨강은 바뀌지 않는다 */
export default function UpDownColorScreen() {
  const { c, market, scheme, upDownPreference, setUpDownPreference } = useTheme();
  const dark = scheme === "dark";

  return (
    <Screen title="상승·하락 색" leading={<BackButton label="설정으로 돌아가기" />} bottomSpace={40}>
      <View style={[s.preview, { backgroundColor: c.card }]} accessibilityLabel={`미리보기 · ${dark ? "다크" : "라이트"} 모드`}>
        <Text style={[font.section, s.previewLabel, { color: c.muted }]}>미리보기 · {dark ? "다크" : "라이트"} 모드</Text>
        {PREVIEW.map((p, i) => (
          <View key={p.name}>
            {i > 0 && <View style={[s.sep, { backgroundColor: c.line }]} />}
            <View style={s.previewRow}>
              <View style={s.fill}>
                <Text style={[font.headline, { color: c.text }]}>{p.name}</Text>
                <Text style={[font.footnote, { color: c.muted }]}>{p.meta}</Text>
              </View>
              <Sparkline values={p.line} color={p.change > 0 ? market.up : market.down} />
              <View style={s.price}>
                <Text style={[font.headline, { color: c.text }]}>{p.price}</Text>
                <ChangePill value={p.change} />
              </View>
            </View>
          </View>
        ))}
        <View style={s.buttons}>
          <View style={[s.fakeButton, { backgroundColor: market.down }]}><Text style={[font.callout, { color: market.onDown }]}>매도</Text></View>
          <View style={[s.fakeButton, { backgroundColor: market.up }]}><Text style={[font.callout, { color: market.onUp }]}>매수</Text></View>
        </View>
      </View>

      <View accessibilityRole="radiogroup" accessibilityLabel="상승·하락 색">
        <GroupedList>
          {OPTIONS.map((o) => {
            const on = o.value === upDownPreference;
            const colors = resolveMarketColors(scheme, o.value);
            return (
              <Pressable
                key={o.value}
                onPress={() => setUpDownPreference(o.value)}
                accessibilityRole="radio"
                accessibilityState={{ checked: on }}
                style={({ pressed }) => [s.option, pressed && { backgroundColor: c.raised }]}
              >
                <View style={s.swatches}>
                  <View style={[s.swatch, { backgroundColor: colors.up }]} />
                  <View style={[s.swatch, { backgroundColor: colors.down }]} />
                </View>
                <View style={s.fill}>
                  <Text style={[font.body, { color: c.text }]}>{o.title}</Text>
                  <Text style={[font.footnote, { color: c.muted }]}>{o.sub(dark)}</Text>
                </View>
                <View style={s.check}>{on && <Icon name="check" size={20} color={c.accent} strokeWidth={2.8} />}</View>
              </Pressable>
            );
          })}
        </GroupedList>
      </View>

      <Text style={[font.footnote, s.note, { color: c.muted }]}>
        차트, 등락률, 매수·매도 버튼, 급등·급락 배지에 함께 적용됩니다. 위험 경고나 로그아웃 같은 빨강은 바뀌지 않아요.
      </Text>
    </Screen>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  preview: { borderRadius: 26, overflow: "hidden" },
  previewLabel: { paddingTop: 12, paddingHorizontal: 16 },
  previewRow: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  sep: { height: StyleSheet.hairlineWidth, marginLeft: 16 },
  price: { width: 84, alignItems: "flex-end", gap: 4 },
  buttons: { flexDirection: "row", gap: 10, paddingHorizontal: 16, paddingBottom: 16, paddingTop: 4 },
  fakeButton: { flex: 1, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center" },
  option: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16, minHeight: 64 },
  swatches: { flexDirection: "row", gap: 4 },
  swatch: { width: 14, height: 14, borderRadius: 7 },
  check: { width: 20 },
  note: { paddingHorizontal: 16, lineHeight: 19 },
});
