import { useState } from "react";
import { Alert, Pressable, StyleSheet, Text, View } from "react-native";
import type { BrokerageBalanceResponse, BrokerageOrderResponse, PageResponse, RuleSet, TradingStatusResponse } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font, tabular } from "@/theme/type";
import { authFetch } from "@/services/auth";
import { useApi } from "@/services/useApi";
import { brokerName, loadBrokerAccount, useTradingMode } from "@/services/mode";
import { clearPendingUnknown } from "@/services/pendingLive";
import { useStocksBySymbols } from "@/services/stockLookup";
import { Badge, EmptyState, GroupedList, Loading, Segmented, withAlpha } from "@/components/ui";
import { feedTime } from "@/lib/feed";
import { formatWon } from "@/lib/format";
import { AssetSummary } from "./AssetSummary";
import { HoldingRow } from "./HoldingRow";
import { StrategyList } from "./StrategyList";

type Section = "holdings" | "orders" | "strategies";
const OPEN = ["PENDING_SUBMIT", "SUBMITTED", "PARTIALLY_FILLED"];
const STATUS_LABEL: Record<string, string> = {
  PENDING_SUBMIT: "보내는 중", SUBMITTED: "미체결", PARTIALLY_FILLED: "일부 체결", FILLED: "체결", CANCELLED: "취소", REJECTED: "거절", UNKNOWN: "확인 필요",
};

/**
 * 내 계좌 · 실전(ADR-113) — 주문용 증권사 연동 계좌의 잔고를 증권사에서 바로 읽는다(저장 잔고와 대조하는 API는 아직 없다).
 * 파일·손 입력 계좌와 합치지 않는다(ADR-115). 오늘 손익은 실전 원장이 없어 보이지 않는다 — 모의 숫자를 대신 쓰지 않는다(ADR-025).
 */
export function LiveAccount() {
  const { c } = useTheme();
  const { broker } = useTradingMode();
  const [section, setSection] = useState<Section>("holdings");
  const balance = useApi<BrokerageBalanceResponse>("/api/brokerage/account/balance", { auth: true, refreshMs: 30_000 });
  const orders = useApi<PageResponse<BrokerageOrderResponse>>("/api/brokerage/orders?page=0&size=20", { auth: true, refreshMs: 15_000 });
  const status = useApi<TradingStatusResponse>("/api/brokerage/trading-status", { auth: true, refreshMs: 60_000 });
  const rules = useApi<RuleSet[]>("/api/quant/rulesets", { auth: true });
  const [checkedAt, setCheckedAt] = useState(() => new Date());
  const [acting, setActing] = useState<number | null>(null);
  const firm = brokerName(broker?.provider);
  const b = balance.data;
  const holdings = (b?.holdings ?? []).filter((h) => Number(h.quantity) > 0);
  const names = useStocksBySymbols([...holdings.map((h) => h.symbol), ...(orders.data?.content ?? []).map((o) => o.symbol)]);

  if (balance.loading) return <Loading />;
  if (!b) {
    return <EmptyState fill={false} title={`${firm} 잔고를 불러오지 못했어요`} sub="연동이 끊겼거나 증권사 응답이 늦어요. 잠시 뒤 다시 시도해 주세요." action={{ label: "다시 시도", onPress: balance.reload }} />;
  }

  const cash = Number(b.cash);
  const total = Number(b.totalEvaluated);
  const list = orders.data?.content ?? [];
  const open = list.filter((o) => OPEN.includes(o.status) || o.status === "UNKNOWN" || o.needsReview);
  const now = new Date();

  // 취소·확인은 한 번에 하나 — 두 번 눌러 두 번 보내지 않게, 실패는 알린다
  const act = async (o: BrokerageOrderResponse, run: () => Promise<Response>, failure: string): Promise<boolean> => {
    if (acting != null) return false;
    setActing(o.id);
    try {
      const r = await run();
      if (!r.ok) Alert.alert(failure, "잠시 뒤 다시 시도해 주세요.");
      return r.ok;
    } catch {
      Alert.alert(failure, "연결이 끊겼어요. 주문 내역을 다시 확인해 주세요.");
      return false;
    } finally {
      setActing(null);
      orders.reload();
      loadBrokerAccount();
    }
  };

  const cancel = (o: BrokerageOrderResponse) => {
    Alert.alert("실전 주문 취소", `${names.get(o.symbol)?.name ?? o.symbol} ${formatWon(o.quantity)}주 ${o.side === "BUY" ? "매수" : "매도"} 주문을 ${firm}에 취소 요청할까요?`, [
      { text: "닫기", style: "cancel" },
      { text: "취소 요청", style: "destructive", onPress: () => act(o, () => authFetch(`/api/brokerage/orders/${o.id}`, { method: "DELETE" }), "취소 요청을 보내지 못했어요") },
    ]);
  };
  const sync = async (o: BrokerageOrderResponse) => {
    // 증권사에서 상태를 다시 읽은 뒤에만 잠금을 푼다
    if (await act(o, () => authFetch(`/api/brokerage/orders/${o.id}/sync`), "체결 여부를 확인하지 못했어요")) clearPendingUnknown(o.symbol, o.side);
  };

  return (
    <>
      {status.data?.halted && (
        <View style={[s.banner, { backgroundColor: withAlpha(c.danger, 0.12), borderColor: c.danger }]}>
          <Text style={[font.subhead, { color: c.danger }]}>{status.data.message ?? "실거래 주문이 일시 중단되었습니다."}</Text>
        </View>
      )}

      <AssetSummary
        label={`실전 총자산 · ${firm}`}
        total={total}
        today={null}
        todayPct={null}
        parts={[
          { label: "현금", value: cash, color: c.disclosure },
          { label: "평가액", value: Math.max(0, total - cash), color: c.accent },
        ]}
      />

      <Segmented<Section>
        accessibilityLabel="내 계좌 구역"
        value={section}
        onChange={setSection}
        options={[
          { value: "holdings", label: `보유 ${holdings.length}` },
          { value: "orders", label: `주문 ${open.length}` },
          { value: "strategies", label: `전략 ${(rules.data ?? []).filter((r) => r.status !== "ARCHIVED").length}` },
        ]}
      />

      {section === "holdings" && (
        <>
          {holdings.length === 0 && <EmptyState fill={false} title={`${firm} 계좌에 보유 종목이 없어요`} />}
          {holdings.map((h) => {
            const qty = Number(h.quantity);
            const avg = Number(h.avgPrice);
            const cur = Number(h.currentPrice);
            const pnl = (cur - avg) * qty;
            return (
              <HoldingRow key={h.symbol} symbol={h.symbol} name={names.get(h.symbol)?.name ?? h.symbol} quantity={qty} avgPrice={avg} value={cur * qty} pnl={pnl} pnlRate={avg > 0 ? ((cur - avg) / avg) * 100 : 0} />
            );
          })}
          <GroupedList>
            <Pressable onPress={() => { balance.reload(); setCheckedAt(new Date()); }} accessibilityRole="button" accessibilityHint="증권사 잔고를 다시 읽습니다" style={s.row}>
              <View style={s.flex}>
                <Text style={[font.subhead, { color: c.text }]}>잔고 조회 · {firm}</Text>
                <Text style={[font.caption, { color: c.muted }]}>
                  {feedTime(checkedAt.toISOString(), now)} · 증권사에서 바로 읽은 값
                  {broker?.apiHealth?.lastErrorCode ? ` · 최근 오류 ${broker.apiHealth.lastErrorCode}` : ""}
                </Text>
              </View>
              <Text style={[font.footnote, { color: c.accent }]}>다시 조회</Text>
            </Pressable>
          </GroupedList>
        </>
      )}

      {section === "orders" && (
        list.length === 0
          ? <EmptyState fill={false} title="실전 주문 내역이 없어요" />
          : (
            <GroupedList>
              {list.map((o) => {
                const unknown = o.status === "UNKNOWN" || o.needsReview;
                return (
                  <View key={o.id} style={s.row}>
                    <View style={s.flex}>
                      <View style={s.orderHead}>
                        <Text style={[font.headline, { color: c.text }]}>{names.get(o.symbol)?.name ?? o.symbol}</Text>
                        <Badge label={o.side === "BUY" ? "매수" : "매도"} tone={o.side === "BUY" ? "up" : "down"} />
                        <Badge label={STATUS_LABEL[o.status] ?? o.status} tone={unknown ? "caution" : OPEN.includes(o.status) ? "live" : "muted"} />
                      </View>
                      <Text style={[font.footnote, tabular, { color: c.muted }]}>
                        {o.limitPrice != null ? `${formatWon(o.limitPrice)}원 · ` : "시장가 · "}{formatWon(o.quantity)}주{o.filledQty ? ` (체결 ${formatWon(o.filledQty)})` : ""} · {feedTime(o.submittedAt, now)}
                      </Text>
                      {o.rejectReason && <Text style={[font.caption, { color: c.danger }]}>{o.rejectReason}</Text>}
                    </View>
                    {unknown ? (
                      <Pressable onPress={() => sync(o)} disabled={acting != null} accessibilityRole="button" accessibilityLabel="체결 여부 확인" style={[s.action, { backgroundColor: c.fill }]}>
                        <Text style={[font.footnote, { color: c.caution }]}>확인</Text>
                      </Pressable>
                    ) : OPEN.includes(o.status) ? (
                      <Pressable onPress={() => cancel(o)} disabled={acting != null} accessibilityRole="button" accessibilityLabel="주문 취소" style={[s.action, { backgroundColor: c.fill }]}>
                        <Text style={[font.footnote, { color: c.danger }]}>취소</Text>
                      </Pressable>
                    ) : null}
                  </View>
                );
              })}
            </GroupedList>
          )
      )}

      {section === "strategies" && <StrategyList rules={rules.data ?? []} />}
    </>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: 2 },
  banner: { padding: 12, borderRadius: 18, borderWidth: 1 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  orderHead: { flexDirection: "row", alignItems: "center", gap: 6, flexWrap: "wrap" },
  action: { height: 32, paddingHorizontal: 14, borderRadius: 16, alignItems: "center", justifyContent: "center" },
});
