import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import type { SchemePreference } from "@/theme/palette";
import { useAuthState } from "@/services/useAuth";
import { logout } from "@/services/auth";
import { unregisterPushToken } from "@/services/notifications";
import { GroupedList, Icon, ListRow, RowIcon, Screen, SectionLabel, Segmented } from "@/components/ui";
import { BackButton } from "@/components/ui/BackButton";

const UPDOWN_LABEL = { auto: "모드에 맞춤", redUp: "빨강 상승", greenUp: "초록 상승" } as const;

/** 설정(ADR-113). 계정·알림·투자 항목은 M7에서 더한다 */
export default function SettingsScreen() {
  const { c, market, schemePreference, setSchemePreference, upDownPreference } = useTheme();
  const router = useRouter();
  const auth = useAuthState();

  return (
    <Screen title="설정" leading={<BackButton />} bottomSpace={48}>
      <SectionLabel>알림</SectionLabel>
      <GroupedList inset={58}>
        <ListRow label="가격 알림" icon={<RowIcon color="#d6334a"><Icon name="bell" size={17} color="#ffffff" strokeWidth={2.2} /></RowIcon>} onPress={() => router.push("/alerts")} />
      </GroupedList>

      <SectionLabel>화면</SectionLabel>
      <GroupedList>
        <View style={s.modeRow}>
          <Text style={[font.body, { color: c.text }]}>화면 모드</Text>
          <View style={s.modeControl}>
            <Segmented<SchemePreference>
              compact
              accessibilityLabel="화면 모드"
              value={schemePreference}
              onChange={setSchemePreference}
              options={[{ value: "system", label: "자동" }, { value: "light", label: "라이트" }, { value: "dark", label: "다크" }]}
            />
          </View>
        </View>
        <ListRow
          label="상승·하락 색"
          value={UPDOWN_LABEL[upDownPreference]}
          onPress={() => router.push("/settings/updown")}
          trailing={
            <View style={s.dots} accessibilityElementsHidden>
              <View style={[s.dot, { backgroundColor: market.up }]} />
              <View style={[s.dot, { backgroundColor: market.down }]} />
            </View>
          }
        />
      </GroupedList>

      <GroupedList>
        {auth === "signedIn" ? (
          // 이 기기의 푸시 토큰을 먼저 해제하고(세션이 살아 있을 때) 세션을 끝낸다
          <ListRow label="로그아웃" destructive onPress={async () => { await logout(unregisterPushToken); router.replace("/"); }} chevron={false} />
        ) : (
          <ListRow label="로그인" onPress={() => router.push("/login")} />
        )}
      </GroupedList>
      <Text style={[font.footnote, s.footer, { color: c.muted }]}>monticker</Text>
    </Screen>
  );
}

const s = StyleSheet.create({
  modeRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, paddingHorizontal: 16, minHeight: 56 },
  modeControl: { width: 190 },
  dots: { flexDirection: "row", gap: 4 },
  dot: { width: 12, height: 12, borderRadius: 6 },
  footer: { textAlign: "center" },
});
