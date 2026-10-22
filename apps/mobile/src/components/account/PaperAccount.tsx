import { useState } from "react";
import { Alert, Linking, Pressable, StyleSheet, Text, View } from "react-native";
import type { PaperPortfolio, RiskExposure, RuleSet, ScreenerQuote } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font, tabular } from "@/theme/type";
import { authFetch } from "@/services/auth";
import { getWebBase } from "@/services/api";
import { useApi } from "@/services/useApi";
import { Badge, Button, Card, EmptyState, GroupedList, Loading, SectionHeader, Segmented } from "@/components/ui";
import { ledgerFlow, ledgerTitle, ledgerTone, type LedgerRow } from "@/lib/account";
import { feedTime } from "@/lib/feed";
import { formatSignedWon, formatWon } from "@/lib/format";
import { AssetSummary } from "./AssetSummary";
import { HoldingRow } from "./HoldingRow";
import { StrategyList } from "./StrategyList";

interface PaperWallet {
  availableCash: number; reservedCash: number; holdingsValue: number; settlementPending: number; totalAssets: number;
  recentLedger: (LedgerRow & { id: number; createdAt: string })[];
}
interface OpenOrder {
  id: number; stockId: number; side: "BUY" | "SELL"; orderType: "MARKET" | "LIMIT"; quantity: number; limitPrice: number | null; filledQty: number; createdAt: string;
}

type Section = "holdings" | "orders" | "strategies";

/** 내 계좌 · 모의(ADR-113) — 총자산·막대, 보유 | 주문 | 전략, 최근 돈의 흐름 */
export function PaperAccount() {
  const { c } = useTheme();
  const [section, setSection] = useState<Section>("holdings");
  const account = useApi<{ initialCapital: number }>("/api/paper/account", { auth: true });
  const hasAccount = account.data != null;
  const wallet = useApi<PaperWallet>(hasAccount ? "/api/wallet" : null, { auth: true, refreshMs: 15_000 });
  const exposure = useApi<RiskExposure>(hasAccount ? "/api/risk/exposure" : null, { auth: true, refreshMs: 30_000 });
  const portfolio = useApi<PaperPortfolio>(hasAccount ? "/api/paper/portfolio" : null, { auth: true, refreshMs: 15_000 });
  const orders = useApi<OpenOrder[]>(hasAccount ? "/api/matching/orders" : null, { auth: true, refreshMs: 15_000 });
  const rules = useApi<RuleSet[]>("/api/quant/rulesets", { auth: true });
  const orderIds = [...new Set((orders.data ?? []).map((o) => o.stockId))];
  const quotes = useApi<{ items: ScreenerQuote[] }>(orderIds.length ? `/api/screener/quotes?ids=${orderIds.join(",")}` : null);
  const names = new Map((quotes.data?.items ?? []).map((q) => [q.stockId, q.name]));

  if (account.loading) return <Loading />;
  if (!hasAccount) return account.error ? <EmptyState fill={false} title="불러오지 못했어요" action={{ label: "다시 시도", onPress: account.reload }} /> : <StartPaper onCreated={account.reload} />;

  const w = wallet.data;
  const holdings = (portfolio.data?.holdings ?? []).filter((h) => h.quantity > 0);
  const open = orders.data ?? [];
  const activeRules = (rules.data ?? []).filter((r) => r.status !== "ARCHIVED");
  const now = new Date();

  const cancel = (o: OpenOrder) => {
    Alert.alert("주문 취소", `${names.get(o.stockId) ?? "종목"} ${formatWon(o.quantity)}주 ${o.side === "BUY" ? "매수" : "매도"} 주문을 취소할까요?`, [
      { text: "닫기", style: "cancel" },
      {
        text: "주문 취소", style: "destructive",
        onPress: async () => {
          await authFetch(`/api/matching/orders/${o.id}`, { method: "DELETE" }).catch(() => undefined);
          orders.reload(); wallet.reload();
        },
      },
    ]);
  };

  return (
    <>
      {w && (
        <AssetSummary
          label="모의 총자산"
          total={Number(w.totalAssets)}
          today={exposure.data ? Number(exposure.data.dailyPnl) : null}
          todayPct={exposure.data?.dailyPnlPct ?? null}
          parts={[
            { label: "현금", value: Number(w.availableCash), color: c.disclosure },
            { label: "예약금", value: Number(w.reservedCash), color: c.caution },
            { label: "평가액", value: Number(w.holdingsValue), color: c.accent },
          ]}
        />
      )}

      <Segmented<Section>
        accessibilityLabel="내 계좌 구역"
        value={section}
        onChange={setSection}
        options={[
          { value: "holdings", label: `보유 ${holdings.length}` },
          { value: "orders", label: `주문 ${open.length}` },
          { value: "strategies", label: `전략 ${activeRules.length}` },
        ]}
      />

      {section === "holdings" && (
        <>
          {holdings.length === 0 && <EmptyState fill={false} title="보유 종목이 없어요" sub="종목 화면에서 모의 매수로 시작해 보세요." />}
          {holdings.map((h) => (
            <HoldingRow key={h.stockId} symbol={h.symbol} name={h.name} quantity={h.quantity} avgPrice={Number(h.avgPrice)} value={Number(h.value)} pnl={Number(h.pnl)} pnlRate={h.pnlRate} />
          ))}
          <View style={s.section}>
            <SectionHeader
              title="최근 돈의 흐름"
              action={<Text onPress={() => Linking.openURL(`${getWebBase()}/wallet/replay`)} accessibilityRole="link" style={[font.subhead, { color: c.accent }]}>오늘의 리플레이</Text>}
            />
            {(w?.recentLedger ?? []).length === 0 ? (
              <Text style={[font.subhead, s.pad, { color: c.muted }]}>아직 돈이 움직인 기록이 없어요.</Text>
            ) : (
              <GroupedList>
                {(w?.recentLedger ?? []).slice(0, 6).map((l) => {
                  const tone = ledgerTone(l.eventType);
                  const flow = ledgerFlow(l.eventType, Number(l.amount));
                  return (
                    <View key={l.id} style={s.ledger}>
                      <View style={s.flex}>
                        <Text style={[font.subhead, { color: c.text }]} numberOfLines={1}>{ledgerTitle(l)}</Text>
                        <Text style={[font.caption, { color: c.muted }]}>{feedTime(l.createdAt, now)}{flow ? ` · ${flow}` : ""}</Text>
                      </View>
                      <Text style={[font.subhead, tabular, s.semibold, { color: tone === "caution" ? c.caution : tone === "positive" ? c.positive : c.soft }]}>
                        {formatSignedWon(Number(l.amount))}
                      </Text>
                    </View>
                  );
                })}
              </GroupedList>
            )}
          </View>
        </>
      )}

      {section === "orders" && (
        open.length === 0
          ? <EmptyState fill={false} title="미체결 주문이 없어요" sub="지정가 주문은 가격이 닿을 때까지 여기에 남아요." />
          : (
            <GroupedList>
              {open.map((o) => (
                <View key={o.id} style={s.ledger}>
                  <View style={s.flex}>
                    <View style={s.orderHead}>
                      <Text style={[font.headline, { color: c.text }]}>{names.get(o.stockId) ?? `#${o.stockId}`}</Text>
                      <Badge label={o.side === "BUY" ? "매수" : "매도"} tone={o.side === "BUY" ? "up" : "down"} />
                    </View>
                    <Text style={[font.footnote, tabular, { color: c.muted }]}>
                      {o.limitPrice != null ? `${formatWon(o.limitPrice)}원 · ` : "시장가 · "}{formatWon(o.quantity)}주{o.filledQty ? ` (체결 ${formatWon(o.filledQty)})` : ""} · {feedTime(o.createdAt, now)}
                    </Text>
                  </View>
                  <Pressable onPress={() => cancel(o)} accessibilityRole="button" accessibilityLabel="주문 취소" style={[s.cancel, { backgroundColor: c.fill }]}>
                    <Text style={[font.footnote, { color: c.danger }]}>취소</Text>
                  </Pressable>
                </View>
              ))}
            </GroupedList>
          )
      )}

      {section === "strategies" && <StrategyList rules={rules.data ?? []} />}
    </>
  );
}

const CAPITALS = [10_000_000, 30_000_000, 100_000_000];

/** 모의 계좌가 없을 때(204) — 시작 자금 고르기(ADR-089) */
function StartPaper({ onCreated }: { onCreated: () => void }) {
  const { c } = useTheme();
  const [capital, setCapital] = useState(CAPITALS[0]);
  const [busy, setBusy] = useState(false);
  const create = async () => {
    setBusy(true);
    try {
      await authFetch("/api/paper/account", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ initialCapital: capital }) });
      onCreated();
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card style={s.start}>
      <Text style={[font.title, { color: c.text }]}>가상의 돈으로 모의투자를 시작해요</Text>
      <Text style={[font.subhead, { color: c.muted }]}>시작 자금을 고르면 바로 지급돼요. 모의투자 손익은 실제 계좌와 무관하고, 언제든 초기화할 수 있어요.</Text>
      <Segmented
        accessibilityLabel="시작 자금"
        value={String(capital)}
        onChange={(v) => setCapital(Number(v))}
        options={CAPITALS.map((v) => ({ value: String(v), label: v >= 100_000_000 ? "1억" : `${(v / 10_000).toLocaleString("ko-KR")}만` }))}
      />
      <Button label="모의투자 시작" onPress={create} busy={busy} />
    </Card>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  semibold: { fontWeight: "600" },
  section: { gap: 8 },
  pad: { paddingHorizontal: 4 },
  ledger: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  orderHead: { flexDirection: "row", alignItems: "center", gap: 6 },
  cancel: { height: 32, paddingHorizontal: 14, borderRadius: 16, alignItems: "center", justifyContent: "center" },
  start: { gap: 12 },
});
