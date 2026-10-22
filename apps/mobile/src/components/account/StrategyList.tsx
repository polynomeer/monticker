import { Linking, StyleSheet, Text, View } from "react-native";
import type { ForwardTestResult, RuleSet } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, tabular } from "@/theme/type";
import { useApi } from "@/services/useApi";
import { getWebBase } from "@/services/api";
import { Badge, Card, EmptyState, GroupedList, ListRow, withAlpha } from "@/components/ui";
import { forwardStats, weeksSince } from "@/lib/account";
import { feedTime } from "@/lib/feed";
import { formatPct } from "@/lib/format";

/** 과거 성과 고지 — 웹 전략 카드와 같은 문구(약관 §96) */
export const PERFORMANCE_NOTICE = "과거 성과가 미래 수익을 보장하지 않습니다. 신호는 모의이며 실제 주문은 나가지 않습니다.";

const STATUS_LABEL: Record<string, string> = { RUNNING: "포워드 테스트", BACKTESTED: "백테스트 완료", DRAFT: "초안", ARCHIVED: "보관" };

/** 내 계좌 › 전략 — 포워드 테스트 중인 전략을 위에, 백테스트·초안은 한 줄 요약. 만들기·편집은 웹 퀀트랩 */
export function StrategyList({ rules }: { rules: RuleSet[] }) {
  const { c } = useTheme();
  const subs = useApi<{ activeSubscriptions?: number }>("/api/quant/signals/summary", { auth: true });
  const visible = rules.filter((r) => r.status !== "ARCHIVED");
  const running = visible.filter((r) => r.status === "RUNNING");
  const others = visible.filter((r) => r.status !== "RUNNING");

  return (
    <>
      {visible.length === 0 && (
        <EmptyState fill={false} title="아직 만든 전략이 없어요" sub="조건 블록으로 전략을 만들고 백테스트·포워드 테스트로 검증해 보세요. 만들기는 웹 퀀트랩에서 해요." />
      )}
      {running.map((r) => <RunningCard key={r.id} rule={r} />)}
      {others.length > 0 && (
        <GroupedList>
          {others.map((r) => {
            const bt = r.performance?.backtest;
            const sub = bt
              ? [bt.totalReturn != null ? `백테스트 ${formatPct(bt.totalReturn, 1)}` : null, bt.reliabilityScore ? `신뢰도 ${bt.reliabilityScore}` : null].filter(Boolean).join(" · ")
              : "백테스트 전";
            return (
              <View key={r.id} style={s.row}>
                <View style={s.flex}>
                  <Text style={[font.headline, { color: c.text }]} numberOfLines={1}>{r.name}</Text>
                  <Text style={[font.footnote, { color: c.muted }]}>{sub}</Text>
                </View>
                <Badge label={STATUS_LABEL[r.status] ?? r.status} tone={r.status === "BACKTESTED" ? "accent" : "muted"} />
              </View>
            );
          })}
        </GroupedList>
      )}
      <GroupedList>
        <ListRow
          label="전략 마켓 둘러보기"
          value={subs.data?.activeSubscriptions ? `구독 중 ${subs.data.activeSubscriptions}개` : undefined}
          onPress={() => Linking.openURL(`${getWebBase()}/quant-lab/market`)}
          accessibilityHint="웹에서 엽니다"
        />
        <ListRow label="전략 만들기·편집" value="웹 퀀트랩" onPress={() => Linking.openURL(`${getWebBase()}/quant-lab`)} accessibilityHint="웹에서 엽니다" />
      </GroupedList>
      <Text style={[font.footnote, s.notice, { color: c.muted }]}>{PERFORMANCE_NOTICE}</Text>
    </>
  );
}

function RunningCard({ rule }: { rule: RuleSet }) {
  const { c, market } = useTheme();
  const fwd = useApi<ForwardTestResult>(`/api/quant/rulesets/${rule.id}/forward-test`, { auth: true, refreshMs: 120_000 });
  const f = fwd.data;
  const stats = f ? forwardStats(f.initialCapital, f.currentEquity, f.equityCurve) : null;
  const match = f?.matchRate ?? rule.performance?.forward?.matchRate ?? null;
  const started = f?.startedAt ?? rule.performance?.forward?.startedAt;
  const last = f?.signals.at(-1);
  const now = new Date();
  const today = last && new Date(last.signalTime).toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" }) === now.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });

  return (
    <Card style={s.card}>
      <View style={s.head}>
        <View style={s.flex}>
          <Text style={[font.headline, { color: c.text }]} numberOfLines={1}>{rule.name}</Text>
          <Text style={[font.footnote, { color: c.muted }]}>v{rule.version}{started ? ` · ${weeksSince(started, now)}주째` : ""}</Text>
        </View>
        <Badge label="포워드 테스트" tone="up" dot />
      </View>
      {/* 낙폭을 먼저 — 웹 W2와 같은 순서(MDD → 기간 → 수익) */}
      <View style={s.stats}>
        <Stat label="MDD" value={stats?.mddPct != null ? formatPct(stats.mddPct, 1) : "—"} color={market.down} />
        <Stat label="누적 수익" value={stats?.returnPct != null ? formatPct(stats.returnPct, 1) : "—"} color={stats?.returnPct != null ? changeColor(stats.returnPct, market, c.text) : c.text} />
        <Stat label="일치율" value={match != null ? `${Math.round(match * 100)}%` : "—"} color={c.text} />
      </View>
      {today && last && (
        <View style={[s.signal, { backgroundColor: withAlpha(c.accent, 0.12) }]}>
          <Text style={[font.footnote, { color: c.body }]}>
            오늘 <Text style={[s.bold, { color: last.direction === "BUY" ? market.up : market.down }]}>{last.direction === "BUY" ? "매수" : "매도"} 신호</Text> · {feedTime(last.signalTime, now)}
          </Text>
          <Text style={[font.caption, { color: c.muted }]}>모의 · 실주문 없음</Text>
        </View>
      )}
    </Card>
  );
}

function Stat({ label, value, color }: { label: string; value: string; color: string }) {
  const { c } = useTheme();
  return (
    <View style={s.stat}>
      <Text style={[font.caption, { color: c.muted }]}>{label}</Text>
      <Text style={[font.headline, tabular, { color }]}>{value}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  bold: { fontWeight: "700" },
  card: { gap: 12 },
  head: { flexDirection: "row", alignItems: "center", gap: 8 },
  stats: { flexDirection: "row", gap: 8 },
  stat: { flex: 1, gap: 2 },
  signal: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingVertical: 8, paddingHorizontal: 12, borderRadius: 14 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  notice: { paddingHorizontal: 16, lineHeight: 19 },
});
