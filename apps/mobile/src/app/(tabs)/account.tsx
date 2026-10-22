import { useState } from "react";
import { Linking, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { useAuthState } from "@/services/useAuth";
import { setTradingMode, useTradingMode, type TradingMode } from "@/services/mode";
import { getWebBase } from "@/services/api";
import { Card, GlassButton, GroupedList, Icon, ListRow, Screen, Segmented } from "@/components/ui";
import { SignInRequired } from "@/components/SignInRequired";

/**
 * 내 계좌(ADR-113) — 모의 | 실전 분할 버튼이 앱 전체 모드를 바꾼다. 실전은 증권사 연동이 있을 때만.
 * 보유·주문·전략 내용은 다음 단계(M5·M6)에서 채운다.
 */
export default function AccountScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const auth = useAuthState();
  const { mode } = useTradingMode();
  const [notice, setNotice] = useState<string | null>(null);

  if (auth === "signedOut") return <SignInRequired message="모의투자 계좌와 연동한 증권 계좌를 보려면 로그인해 주세요" />;

  const change = async (next: TradingMode) => {
    setNotice(null);
    const ok = await setTradingMode(next);
    if (!ok) setNotice("실전은 주문용 증권사 연동이 있어야 쓸 수 있어요. 연동은 웹의 실전투자에서 합니다.");
  };

  return (
    <Screen
      title="내 계좌"
      leading={
        <View style={s.segment}>
          <Segmented
            accessibilityLabel="계좌 모드"
            value={mode}
            onChange={change}
            options={[{ value: "paper", label: "모의" }, { value: "live", label: "실전" }]}
          />
        </View>
      }
      actions={
        <GlassButton onPress={() => router.push("/settings")} accessibilityLabel="프로필·설정">
          <Icon name="user" size={20} color={c.text} />
        </GlassButton>
      }
    >
      {notice && (
        <Card style={s.notice}>
          <Text style={[font.subhead, { color: c.body }]}>{notice}</Text>
          <Text style={[font.subhead, { color: c.accent }]} onPress={() => Linking.openURL(`${getWebBase()}/brokerage`)} accessibilityRole="link">웹에서 연동하기</Text>
        </Card>
      )}
      <GroupedList>
        <ListRow label="다른 증권 계좌" value="파일·직접 입력" onPress={() => router.push("/accounts")} />
      </GroupedList>
    </Screen>
  );
}

const s = StyleSheet.create({
  segment: { width: 150 },
  notice: { gap: 6 },
});
