import { Linking, Text } from "react-native";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { getWebBase } from "@/services/api";
import { Card } from "@/components/ui";

/** 내 계좌 · 실전 자리 — 증권사 잔고·보유는 M6에서 붙인다 */
export function LiveAccount() {
  const { c } = useTheme();
  return (
    <Card>
      <Text style={[font.subhead, { color: c.body }]}>실전 계좌 잔고와 주문은 곧 앱에서 볼 수 있어요.</Text>
      <Text style={[font.subhead, { color: c.accent }]} onPress={() => Linking.openURL(`${getWebBase()}/brokerage`)} accessibilityRole="link">웹 실전투자 열기</Text>
    </Card>
  );
}
