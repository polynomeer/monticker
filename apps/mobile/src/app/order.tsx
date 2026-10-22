import { useEffect, useMemo, useRef, useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import * as Haptics from "expo-haptics";
import type { PaperPortfolio, StockPriceResponse, StockSearchItem } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, radius, tabular } from "@/theme/type";
import { authFetch } from "@/services/auth";
import { useApi } from "@/services/useApi";
import { useTradingMode } from "@/services/mode";
import { useRiskPreview } from "@/services/riskPreview";
import { Badge, Button, Chip, GlassButton, Icon, Loading, withAlpha } from "@/components/ui";
import { formatPct, formatWon } from "@/lib/format";
import { clampQuantity, isOrderable, maxQuantity, newIdempotencyKey, parseAmount, quickQuantity, type Side } from "@/lib/order";
import { RULE_LABEL, ruleValue, visibleChecks } from "@/lib/risk";
import { LiveOrderSheet } from "@/components/order/LiveOrderSheet";

/** 시안의 감정 태그 4개 → 서버 EmotionType(ADR-085) */
const EMOTIONS = [
  { label: "확신", value: "CONFIDENT" },
  { label: "계획대로", value: "PLANNED" },
  { label: "FOMO", value: "FOMO" },
  { label: "불안", value: "ANXIOUS" },
];

interface PaperOrderResult { status: "FILLED" | "PENDING" | string; tradeId: number | null; price: number | null; quantity: number }

/**
 * 주문 시트(ADR-113) — 지금 모드로만 주문한다. 시트 안에서 모드를 바꾸지 않는다.
 * 모의: 리스크 미리보기(ADR-092) + 감정 태그 + 지정가/시장가. 실전은 LiveOrderSheet.
 */
export default function OrderScreen() {
  const { mode } = useTradingMode();
  const { symbol, side } = useLocalSearchParams<{ symbol: string; side?: Side }>();
  if (mode === "live") return <LiveOrderSheet symbol={symbol} initialSide={side === "SELL" ? "SELL" : "BUY"} />;
  return <PaperOrderSheet symbol={symbol} initialSide={side === "SELL" ? "SELL" : "BUY"} />;
}

function PaperOrderSheet({ symbol, initialSide }: { symbol: string; initialSide: Side }) {
  const { c, market } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [side, setSide] = useState<Side>(initialSide);
  const [orderType, setOrderType] = useState<"LIMIT" | "MARKET">("LIMIT");
  const [limitText, setLimitText] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [emotion, setEmotion] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const attempt = useRef(newIdempotencyKey());

  const search = useApi<StockSearchItem[]>(`/api/stocks/search?query=${encodeURIComponent(symbol)}`);
  const stock = search.data?.find((s) => s.symbol === symbol) ?? null;
  const price = useApi<StockPriceResponse>(stock ? `/api/stocks/${stock.id}/price` : null, { refreshMs: 5_000 });
  const wallet = useApi<{ availableCash: number }>("/api/wallet", { auth: true });
  const portfolio = useApi<PaperPortfolio>("/api/paper/portfolio", { auth: true });

  const current = price.data?.price ?? 0;
  useEffect(() => { if (!limitText && current > 0) setLimitText(formatWon(current)); }, [current, limitText]);

  const isLimit = orderType === "LIMIT";
  const unitPrice = isLimit ? parseAmount(limitText) : current;
  const holding = portfolio.data?.holdings.find((h) => h.stockId === stock?.id)?.quantity ?? 0;
  const max = maxQuantity(side, Number(wallet.data?.availableCash ?? 0), unitPrice, holding);
  const valid = isOrderable(quantity, unitPrice, max);
  const amount = unitPrice * quantity;

  const riskInput = useMemo(
    () => (stock && valid ? { stockId: stock.id, side, quantity, estimatedPrice: unitPrice } : null),
    [stock, valid, side, quantity, unitPrice],
  );
  const risk = useRiskPreview(riskInput);
  const blocked = risk.data != null && !risk.data.approved;

  if (search.loading || !stock) return <Loading />;

  const sideColor = side === "BUY" ? market.up : market.down;
  const sideFg = side === "BUY" ? market.onUp : market.onDown;
  const verb = side === "BUY" ? "매수" : "매도";

  const submit = async () => {
    if (!valid || busy || blocked) return;
    setBusy(true);
    setError(null);
    try {
      const r = await authFetch("/api/paper/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Idempotency-Key": attempt.current },
        body: JSON.stringify({ stockId: stock.id, side, orderType, quantity, ...(isLimit ? { limitPrice: unitPrice } : {}) }),
      });
      const body = (await r.json().catch(() => null)) as (PaperOrderResult & { message?: string }) | null;
      if (!r.ok || !body) { setError(body?.message ?? "주문하지 못했어요."); return; }
      attempt.current = newIdempotencyKey();       // 다음 주문은 새 키
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success).catch(() => undefined);
      if (body.status === "PENDING") {
        setDone(`${formatWon(unitPrice)}원 ${formatWon(quantity)}주 ${verb} 주문을 접수했어요. 가격이 닿으면 체결되고, 내 계좌 › 주문에서 취소할 수 있어요.`);
        return;
      }
      if (body.tradeId && emotion) {
        await authFetch(`/api/paper/trades/${body.tradeId}/emotion`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ emotion, memo: null }),
        }).catch(() => undefined);
      }
      setDone(`${formatWon(body.price ?? unitPrice)}원에 ${formatWon(body.quantity ?? quantity)}주 ${verb} 체결됐어요.`);
    } catch {
      setError("연결이 끊겼어요. 내 계좌에서 주문이 들어갔는지 확인한 뒤 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView style={[s.fill, { backgroundColor: c.bg }]} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <View style={[s.header, { paddingTop: Platform.OS === "ios" ? 14 : insets.top + 8 }]}>
        <GlassButton onPress={() => router.back()} accessibilityLabel="닫기"><Icon name="close" size={20} color={c.text} /></GlassButton>
        <View style={s.headerTitle}>
          <Text style={[font.headline, { color: c.text }]} accessibilityRole="header">{stock.name} 모의 주문</Text>
          {price.data?.changeRate != null && (
            <Text style={[font.footnote, tabular, s.semibold, { color: changeColor(price.data.changeRate, market, c.muted) }]}>
              {formatWon(current)} {formatPct(price.data.changeRate)}
            </Text>
          )}
        </View>
        <View style={s.badge}><Badge label="모의" tone="caution" /></View>
      </View>

      {done ? (
        <View style={s.done}>
          <Icon name="check" size={40} color={c.positive} strokeWidth={2.6} />
          <Text style={[font.headline, s.center, { color: c.text }]}>{done}</Text>
          <Text style={[font.footnote, s.center, { color: c.muted }]}>모의투자 손익은 실제 계좌와 무관합니다.</Text>
          <Button label="닫기" tone="plain" onPress={() => router.back()} style={s.doneButton} />
        </View>
      ) : (
        <>
          <ScrollView contentContainerStyle={s.body} keyboardShouldPersistTaps="handled">
            <View accessibilityRole="tablist" accessibilityLabel="주문 방향" style={[s.sides, { backgroundColor: c.fill }]}>
              {(["BUY", "SELL"] as Side[]).map((sd) => {
                const on = sd === side;
                return (
                  <Pressable
                    key={sd}
                    onPress={() => { setSide(sd); setQuantity(1); }}
                    accessibilityRole="tab"
                    accessibilityState={{ selected: on }}
                    style={[s.sideButton, on && { backgroundColor: sd === "BUY" ? market.up : market.down }]}
                  >
                    <Text style={[font.callout, { color: on ? (sd === "BUY" ? market.onUp : market.onDown) : c.soft }]}>{sd === "BUY" ? "매수" : "매도"}</Text>
                  </Pressable>
                );
              })}
            </View>

            <View style={[s.form, { backgroundColor: c.fill }]}>
              <View style={s.formRow}>
                <Text style={[font.body, { color: c.text }]}>주문 유형</Text>
                <Pressable
                  onPress={() => setOrderType(isLimit ? "MARKET" : "LIMIT")}
                  accessibilityRole="button"
                  accessibilityHint="지정가와 시장가를 바꿉니다"
                  style={[s.typeButton, { backgroundColor: c.line }]}
                >
                  <Text style={[font.subhead, { color: c.text }]}>{isLimit ? "지정가" : "시장가"}</Text>
                  <Icon name="sort" size={14} color={c.muted} />
                </Pressable>
              </View>
              {isLimit && (
                <>
                  <View style={[s.sep, { backgroundColor: c.line }]} />
                  <View style={s.formRow}>
                    <Text style={[font.body, { color: c.text }]}>지정가</Text>
                    <View style={s.priceInput}>
                      <TextInput
                        value={limitText}
                        onChangeText={(t) => setLimitText(t ? formatWon(parseAmount(t)) : "")}
                        keyboardType="number-pad"
                        accessibilityLabel="지정가(원)"
                        style={[font.headline, tabular, s.input, { color: c.text }]}
                      />
                      <Text style={[font.body, { color: c.muted }]}>원</Text>
                    </View>
                  </View>
                </>
              )}
              <View style={[s.sep, { backgroundColor: c.line }]} />
              <View style={[s.formRow, s.qtyRow]}>
                <View>
                  <Text style={[font.body, { color: c.text }]}>수량</Text>
                  <Text style={[font.caption, { color: c.muted }]}>최대 {formatWon(max)}주</Text>
                </View>
                <View style={[s.stepper, { backgroundColor: c.line }]}>
                  <Pressable onPress={() => setQuantity((q) => clampQuantity(q - 1, max))} accessibilityRole="button" accessibilityLabel="1주 빼기" style={s.step}>
                    <Icon name="minus" size={18} color={c.text} />
                  </Pressable>
                  <TextInput
                    value={String(quantity)}
                    onChangeText={(t) => setQuantity(clampQuantity(parseAmount(t), max))}
                    keyboardType="number-pad"
                    accessibilityLabel="수량(주)"
                    style={[font.headline, tabular, s.qty, { color: c.text }]}
                  />
                  <Pressable onPress={() => setQuantity((q) => clampQuantity(q + 1, max))} accessibilityRole="button" accessibilityLabel="1주 더하기" style={s.step}>
                    <Icon name="plus" size={18} color={c.text} />
                  </Pressable>
                </View>
              </View>
            </View>

            <View style={s.quick} accessibilityLabel="빠른 수량">
              {[10, 25, 50, 100].map((pct) => (
                <Pressable key={pct} onPress={() => setQuantity(quickQuantity(max, pct))} accessibilityRole="button" style={[s.quickButton, { backgroundColor: c.fill }]}>
                  <Text style={[font.footnote, { color: c.body }]}>{pct === 100 ? "최대" : `${pct}%`}</Text>
                </Pressable>
              ))}
            </View>

            <View style={[s.riskBox, { backgroundColor: c.card }]}>
              <View style={s.riskHead}>
                <Text style={[font.subhead, s.semibold, { color: c.text }]}>주문 전 리스크 체크</Text>
                {risk.data && !risk.pending && (
                  <View style={[s.verdict, { backgroundColor: withAlpha(risk.data.approved ? c.positive : c.danger, 0.14) }]}>
                    <Text accessibilityLiveRegion="polite" style={[font.caption, s.bold, { color: risk.data.approved ? c.positive : c.danger }]}>
                      {risk.data.approved ? "통과" : "한도 초과"}
                    </Text>
                  </View>
                )}
                {risk.pending && <Text style={[font.caption, { color: c.muted }]}>점검 중…</Text>}
              </View>
              {!risk.data && !risk.pending && (
                <Text style={[font.footnote, { color: c.muted }]}>{risk.error ?? "수량과 가격을 정하면 손실 한도·집중도에 걸리는지 바로 보여 드려요."}</Text>
              )}
              {risk.data && visibleChecks(risk.data.checks).map((r) => (
                <View key={r.rule} style={[s.riskRow, risk.pending && s.dim]}>
                  <View style={s.riskLabel}>
                    <Icon name={r.passed ? "check" : "alert"} size={15} color={r.passed ? c.positive : c.danger} strokeWidth={2.4} />
                    <Text style={[font.subhead, { color: c.body }]}>{RULE_LABEL[r.rule] ?? r.rule}</Text>
                  </View>
                  <Text style={[font.subhead, tabular, { color: r.passed ? c.text : c.danger }]}>{ruleValue(r)}</Text>
                </View>
              ))}
            </View>

            <View style={s.emotions}>
              <Text style={[font.footnote, s.emotionLabel, { color: c.muted }]}>지금 기분은? (감정 태그)</Text>
              <View style={s.emotionRow}>
                {EMOTIONS.map((e) => (
                  <Chip key={e.value} label={e.label} selected={emotion === e.value} onPress={() => setEmotion((v) => (v === e.value ? null : e.value))} />
                ))}
              </View>
            </View>
          </ScrollView>

          <View style={[s.footer, { paddingBottom: Math.max(insets.bottom, 16) + 8 }]}>
            {error && <Text accessibilityRole="alert" style={[font.footnote, { color: c.danger }]}>{error}</Text>}
            {blocked && <Text style={[font.footnote, { color: c.danger }]}>리스크 한도를 넘어 주문할 수 없어요. 수량을 줄이거나 설정 › 리스크 한도를 확인해 주세요.</Text>}
            <View style={s.total}>
              <Text style={[font.subhead, { color: c.muted }]}>{side === "BUY" ? "예상 금액 · 예약금으로 묶임" : "예상 금액"}</Text>
              <Text style={[font.subhead, tabular, s.bold, { color: c.text }]}>{valid ? `${formatWon(amount)}원` : "—"}</Text>
            </View>
            <Pressable
              onPress={submit}
              disabled={!valid || busy || blocked}
              accessibilityRole="button"
              accessibilityState={{ disabled: !valid || busy || blocked, busy }}
              style={({ pressed }) => [s.submit, { backgroundColor: sideColor, opacity: !valid || blocked ? 0.4 : pressed || busy ? 0.85 : 1 }]}
            >
              <Text style={[font.headline, { color: sideFg }]}>
                {busy ? "주문 중…" : isLimit ? `${formatWon(unitPrice)}원에 ${formatWon(quantity)}주 모의 ${verb}` : `시장가로 ${formatWon(quantity)}주 모의 ${verb}`}
              </Text>
            </Pressable>
          </View>
        </>
      )}
    </KeyboardAvoidingView>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  center: { textAlign: "center" },
  semibold: { fontWeight: "600" },
  bold: { fontWeight: "700" },
  dim: { opacity: 0.5 },
  header: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingHorizontal: 16, paddingBottom: 4 },
  headerTitle: { alignItems: "center", flex: 1 },
  badge: { width: 44, alignItems: "center" },
  body: { padding: 16, gap: 14 },
  sides: { flexDirection: "row", padding: 3, borderRadius: 22 },
  sideButton: { flex: 1, height: 38, borderRadius: 19, alignItems: "center", justifyContent: "center" },
  form: { borderRadius: radius.card, overflow: "hidden" },
  formRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingHorizontal: 16, height: 52 },
  qtyRow: { height: 60 },
  sep: { height: StyleSheet.hairlineWidth, marginLeft: 16 },
  typeButton: { height: 32, paddingHorizontal: 12, borderRadius: 16, flexDirection: "row", alignItems: "center", gap: 4 },
  priceInput: { flexDirection: "row", alignItems: "baseline", gap: 4 },
  input: { width: 120, textAlign: "right", paddingVertical: 0 },
  stepper: { flexDirection: "row", alignItems: "center", gap: 4, padding: 3, borderRadius: 20 },
  step: { width: 44, height: 32, alignItems: "center", justifyContent: "center" },
  qty: { width: 56, textAlign: "center", paddingVertical: 0 },
  quick: { flexDirection: "row", gap: 8 },
  quickButton: { flex: 1, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center" },
  riskBox: { borderRadius: radius.card, padding: 16, gap: 10 },
  riskHead: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  verdict: { paddingHorizontal: 10, paddingVertical: 2, borderRadius: 10 },
  riskRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  riskLabel: { flexDirection: "row", alignItems: "center", gap: 8 },
  emotions: { gap: 8 },
  emotionLabel: { paddingHorizontal: 4 },
  emotionRow: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  footer: { paddingHorizontal: 16, paddingTop: 12, gap: 8 },
  total: { flexDirection: "row", justifyContent: "space-between", paddingHorizontal: 4 },
  submit: { height: 54, borderRadius: 27, alignItems: "center", justifyContent: "center" },
  done: { flex: 1, alignItems: "center", justifyContent: "center", padding: 32, gap: 12 },
  doneButton: { marginTop: 12, alignSelf: "stretch" },
});
