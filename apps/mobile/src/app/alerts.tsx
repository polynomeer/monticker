import { Pressable, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import type { AlertRule, ScreenerQuote } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { useAuthState } from "@/services/useAuth";
import { useApi } from "@/services/useApi";
import { Badge, EmptyState, GroupedList, Loading, Screen } from "@/components/ui";
import { BackButton } from "@/components/ui/BackButton";
import { SignInRequired } from "@/components/SignInRequired";
import { formatWon } from "@/lib/format";

const RULE_LABEL: Record<string, string> = {
  PRICE_ABOVE: "목표가 이상",
  PRICE_BELOW: "목표가 이하",
  VOLUME_SURGE: "거래량 급등",
};

/** 가격 알림 규칙 목록. 규칙 응답에는 종목 id만 있어 이름·심볼은 시세 조회로 붙인다 */
export default function AlertsScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const auth = useAuthState();
  const rules = useApi<AlertRule[]>(auth === "signedIn" ? "/api/alerts/rules" : null, { auth: true });
  const ids = [...new Set((rules.data ?? []).map((r) => r.stockId).filter((id): id is number => id != null))].slice(0, 50);
  const quotes = useApi<{ items: ScreenerQuote[] }>(ids.length ? `/api/screener/quotes?ids=${ids.join(",")}` : null);
  const byId = new Map((quotes.data?.items ?? []).map((q) => [q.stockId, q]));

  if (auth === "signedOut") return <SignInRequired message="알림 규칙을 보려면 로그인해 주세요" />;
  if (auth === "unknown" || rules.loading) return <Loading />;

  const list = rules.data ?? [];
  return (
    <Screen title="가격 알림" leading={<BackButton />} bottomSpace={40} onRefresh={rules.reload}>
      {list.length === 0 ? (
        <EmptyState fill={false} title="설정된 알림이 없어요" sub="종목 화면에서 목표가 알림을 설정할 수 있어요." action={{ label: "종목 찾기", onPress: () => router.push("/search") }} />
      ) : (
        <GroupedList>
          {list.map((r) => {
            const q = r.stockId != null ? byId.get(r.stockId) : undefined;
            const cond = parse(r.conditionJson);
            return (
              <Pressable
                key={r.id}
                disabled={!q}
                onPress={() => q && router.push(`/stocks/${q.symbol}`)}
                accessibilityRole="button"
                style={({ pressed }) => [s.row, pressed && { backgroundColor: c.raised }]}
              >
                <View style={s.fill}>
                  <Text style={[font.headline, { color: c.text }]}>{q?.name ?? "종목"}</Text>
                  <Text style={[font.footnote, { color: c.muted }]}>
                    {RULE_LABEL[r.ruleType] ?? r.ruleType}{cond.threshold != null ? ` · ${formatWon(Number(cond.threshold))}원` : ""}
                  </Text>
                </View>
                <Badge label={r.isActive ? "켜짐" : "꺼짐"} tone={r.isActive ? "accent" : "muted"} />
              </Pressable>
            );
          })}
        </GroupedList>
      )}
    </Screen>
  );
}

function parse(json: string): { threshold?: number | string } {
  try { return JSON.parse(json); } catch { return {}; }
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
});
