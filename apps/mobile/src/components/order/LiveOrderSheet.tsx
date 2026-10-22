import { useEffect, useRef, useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import type { BrokerageBalanceResponse, BrokerageOrderResponse, RiskLimits, StockPriceResponse, StockSearchItem, TradingStatusResponse } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, radius, tabular } from "@/theme/type";
import { authFetch } from "@/services/auth";
import { useApi } from "@/services/useApi";
import { brokerName, useTradingMode } from "@/services/mode";
import { Badge, Button, GlassButton, Icon, Loading, withAlpha } from "@/components/ui";
import { formatPct, formatWon } from "@/lib/format";
import { clampQuantity, isOrderable, maxQuantity, newIdempotencyKey, parseAmount, quickQuantity, type Side } from "@/lib/order";
import { classifyLiveResult, concentrationAfter, DEVIATION_WARN_PCT, priceDeviationPct, type LiveOutcome } from "@/lib/liveOrder";
import { clearPendingUnknown, markPendingUnknown, usePendingUnknown } from "@/services/pendingLive";
import { HoldToConfirm } from "./HoldToConfirm";

/**
 * 실전 주문 시트(ADR-113·025·057). 연동한 증권사 계좌로 바로 나간다.
 * - 길게 눌러야 제출된다. 시트 안에서 모의로 바꿀 수 없다.
 * - 리스크는 서버 게이트가 최종 판정한다(422). 화면은 증권사 잔고로 집중도만 미리 보고, 모의 데이터는 쓰지 않는다.
 * - 거래 중지(킬 스위치)면 제출을 막는다.
 * - 결과 불명(5xx·확정 아닌 4xx·연결 끊김·UNKNOWN)이면 다시 보내지 않는다. 결과 불명은 기기에 남겨(pendingLive)
 *   시트를 다시 열어도 같은 종목·방향 주문을 막고, 주문 내역을 확인했다고 누를 때까지 풀지 않는다.
 *   중복 방지 키(X-Idempotency-Key)는 보조 장치일 뿐이다 — 서버 필터는 2xx만 기억하므로 결과 불명 재전송을 막지 못한다.
 */
export function LiveOrderSheet({ symbol, initialSide }: { symbol: string; initialSide: Side }) {
  const { c, market } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { broker } = useTradingMode();
  const [side, setSide] = useState<Side>(initialSide);
  const [orderType, setOrderType] = useState<"LIMIT" | "MARKET">("LIMIT");
  const [limitText, setLimitText] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<LiveOutcome | null>(null);
  const attempt = useRef(newIdempotencyKey());
  const inflight = useRef(false);
  const pendingSince = usePendingUnknown(symbol, side);

  const search = useApi<StockSearchItem[]>(`/api/stocks/search?query=${encodeURIComponent(symbol)}`);
  const stock = search.data?.find((s) => s.symbol === symbol) ?? null;
  const price = useApi<StockPriceResponse>(stock ? `/api/stocks/${stock.id}/price` : null, { refreshMs: 5_000 });
  const balance = useApi<BrokerageBalanceResponse>("/api/brokerage/account/balance", { auth: true, refreshMs: 30_000 });
  const status = useApi<TradingStatusResponse>("/api/brokerage/trading-status", { auth: true, refreshMs: 30_000 });
  const limits = useApi<RiskLimits>("/api/risk/limits", { auth: true });

  const current = price.data?.price ?? 0;
  useEffect(() => { if (!limitText && current > 0) setLimitText(formatWon(current)); }, [current, limitText]);

  if (search.loading || !stock) return <Loading />;

  const isLimit = orderType === "LIMIT";
  const unitPrice = isLimit ? parseAmount(limitText) : current;
  const b = balance.data;
  const holding = Number(b?.holdings.find((h) => h.symbol === symbol)?.quantity ?? 0);
  const max = maxQuantity(side, Number(b?.cash ?? 0), unitPrice, holding);
  const valid = isOrderable(quantity, unitPrice, max);
  // 거래 상태를 확인하지 못했으면 중지된 것으로 본다
  const halted = status.data?.halted === true || (!status.loading && status.data == null);
  const concentration = b
    ? concentrationAfter(Number(b.totalEvaluated), b.holdings.map((h) => ({ symbol: h.symbol, quantity: Number(h.quantity), currentPrice: Number(h.currentPrice) })), symbol, side, quantity, unitPrice)
    : null;
  const concLimit = limits.data ? Number(limits.data.concentrationLimitPct) : null;
  const concOver = concentration != null && concLimit != null && side === "BUY" && concentration > concLimit;
  const verb = side === "BUY" ? "매수" : "매도";
  const firm = brokerName(broker?.provider);
  const sideColor = side === "BUY" ? market.up : market.down;
  const sideFg = side === "BUY" ? market.onUp : market.onDown;
  const deviation = isLimit ? priceDeviationPct(unitPrice, current) : null;
  const farFromPrice = deviation != null && Math.abs(deviation) > DEVIATION_WARN_PCT;
  const blocked = !valid || halted || concOver || !b || !broker || !!pendingSince || (!isLimit && !(current > 0));

  const submit = async () => {
    // 누르는 사이 상태가 바뀌었을 수 있어 여기서 다시 본다. inflight는 렌더를 기다리지 않는 중복 제출 방지
    if (blocked || inflight.current) return;
    inflight.current = true;
    setBusy(true);
    let httpStatus: number | null = null;
    let body: (Partial<BrokerageOrderResponse> & { message?: string }) | null = null;
    try {
      const r = await authFetch("/api/brokerage/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Idempotency-Key": attempt.current },
        body: JSON.stringify({ symbol, side, orderType, quantity, ...(isLimit ? { limitPrice: unitPrice } : {}) }),
      });
      httpStatus = r.status;
      body = await r.json().catch(() => null);
    } catch {
      httpStatus = null;
    } finally {
      inflight.current = false;
      setBusy(false);
    }
    const result = classifyLiveResult(httpStatus, body);
    if (result.kind === "unknown") markPendingUnknown(symbol, side);
    else attempt.current = newIdempotencyKey();
    setOutcome(result);
    if (result.kind === "accepted") balance.reload();
  };

  return (
    <KeyboardAvoidingView style={[s.fill, { backgroundColor: c.bg }]} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <View style={[s.header, { paddingTop: Platform.OS === "ios" ? 14 : insets.top + 8 }]}>
        <GlassButton onPress={() => router.back()} accessibilityLabel="닫기"><Icon name="close" size={20} color={c.text} /></GlassButton>
        <View style={s.headerTitle}>
          <Text style={[font.headline, { color: c.text }]} accessibilityRole="header">{stock.name} 실전 주문</Text>
          {price.data?.changeRate != null && (
            <Text style={[font.footnote, tabular, s.semibold, { color: changeColor(price.data.changeRate, market, c.muted) }]}>
              {formatWon(current)} {formatPct(price.data.changeRate)}
            </Text>
          )}
        </View>
        <View style={s.badge}><Badge label="실전" tone="live" /></View>
      </View>

      {outcome && outcome.kind !== "rejected" ? (
        <View style={s.done}>
          <Icon name={outcome.kind === "accepted" ? "check" : "alert"} size={40} color={outcome.kind === "accepted" ? c.positive : c.caution} strokeWidth={2.6} />
          <Text style={[font.headline, s.center, { color: c.text }]}>{outcome.message}</Text>
          <Button label="내 계좌에서 보기" tone="plain" onPress={() => { router.back(); router.navigate("/account"); }} style={s.doneButton} />
          <Button label="닫기" tone="plain" onPress={() => router.back()} style={s.doneButton} />
        </View>
      ) : (
        <>
          <ScrollView contentContainerStyle={s.body} keyboardShouldPersistTaps="handled">
            <View style={[s.warning, { backgroundColor: c.liveBg, borderColor: c.liveBorder }]} accessibilityRole="alert">
              <Icon name="alert" size={18} color={c.live} />
              <Text style={[font.subhead, s.flex, { color: c.live }]}>{firm} 계좌로 바로 주문됩니다. 체결되면 되돌릴 수 없어요.</Text>
            </View>
            {halted && (
              <View style={[s.warning, { backgroundColor: withAlpha(c.danger, 0.12), borderColor: c.danger }]}>
                <Text style={[font.subhead, { color: c.danger }]}>{status.data?.message ?? "실거래 주문 가능 여부를 확인하지 못해 주문을 막았어요."}</Text>
              </View>
            )}
            {pendingSince && (
              <View style={[s.pending, { backgroundColor: c.cautionBg, borderColor: c.caution }]}>
                <Text style={[font.subhead, { color: c.caution }]}>
                  {`이 종목 ${verb} 주문의 결과를 아직 확인하지 못했어요. 중복 주문을 막으려고 새 주문을 잠갔어요. 내 계좌 › 실전 › 주문에서 체결 여부를 먼저 확인해 주세요.`}
                </Text>
                <View style={s.pendingActions}>
                  <Text onPress={() => { router.back(); router.navigate("/account"); }} accessibilityRole="link" style={[font.subhead, s.semibold, { color: c.accent }]}>주문 내역 보기</Text>
                  <Text onPress={() => clearPendingUnknown(symbol, side)} accessibilityRole="button" style={[font.subhead, { color: c.muted }]}>확인했어요</Text>
                </View>
              </View>
            )}

            <View accessibilityRole="tablist" accessibilityLabel="주문 방향" style={[s.sides, { backgroundColor: c.fill }]}>
              {(["BUY", "SELL"] as Side[]).map((sd) => {
                const on = sd === side;
                return (
                  <Pressable key={sd} onPress={() => { setSide(sd); setQuantity(1); setOutcome(null); }} accessibilityRole="tab" accessibilityState={{ selected: on }}
                    style={[s.sideButton, on && { backgroundColor: sd === "BUY" ? market.up : market.down }]}>
                    <Text style={[font.callout, { color: on ? (sd === "BUY" ? market.onUp : market.onDown) : c.soft }]}>{sd === "BUY" ? "매수" : "매도"}</Text>
                  </Pressable>
                );
              })}
            </View>

            <View style={[s.form, { backgroundColor: c.fill }]}>
              <View style={s.formRow}>
                <Text style={[font.body, { color: c.text }]}>주문 유형</Text>
                <Pressable onPress={() => setOrderType(isLimit ? "MARKET" : "LIMIT")} accessibilityRole="button" accessibilityHint="지정가와 시장가를 바꿉니다" style={[s.typeButton, { backgroundColor: c.line }]}>
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
                      <TextInput value={limitText} onChangeText={(t) => setLimitText(t ? formatWon(parseAmount(t)) : "")} keyboardType="number-pad" accessibilityLabel="지정가(원)" style={[font.headline, tabular, s.input, { color: c.text }]} />
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
                  <Pressable onPress={() => setQuantity((q) => clampQuantity(q - 1, max))} accessibilityRole="button" accessibilityLabel="1주 빼기" style={s.step}><Icon name="minus" size={18} color={c.text} /></Pressable>
                  <TextInput value={String(quantity)} onChangeText={(t) => setQuantity(clampQuantity(parseAmount(t), max))} keyboardType="number-pad" accessibilityLabel="수량(주)" style={[font.headline, tabular, s.qty, { color: c.text }]} />
                  <Pressable onPress={() => setQuantity((q) => clampQuantity(q + 1, max))} accessibilityRole="button" accessibilityLabel="1주 더하기" style={s.step}><Icon name="plus" size={18} color={c.text} /></Pressable>
                </View>
              </View>
            </View>

            <View style={s.quick}>
              {[10, 25, 50, 100].map((pct) => (
                <Pressable key={pct} onPress={() => setQuantity(quickQuantity(max, pct))} accessibilityRole="button" style={[s.quickButton, { backgroundColor: c.fill }]}>
                  <Text style={[font.footnote, { color: c.body }]}>{pct === 100 ? "최대" : `${pct}%`}</Text>
                </Pressable>
              ))}
            </View>

            <View style={[s.riskBox, { backgroundColor: c.card }]}>
              <View style={s.riskHead}>
                <Text style={[font.subhead, s.semibold, { color: c.text }]}>주문 전 리스크 체크</Text>
                <Text style={[font.caption, { color: concOver ? c.danger : c.muted }]}>{concOver ? "한도 초과" : "제출 때 서버가 판정"}</Text>
              </View>
              <RiskRow label="종목 집중도" value={concentration != null && concLimit != null ? `${concentration.toFixed(1)}% / ${concLimit}%` : "—"} ok={!concOver} />
              <RiskRow label="일간 손실 한도" value={limits.data ? `서버 검증 · ${Number(limits.data.dailyLossLimitPct)}%` : "서버 검증"} ok />
              <RiskRow label="시간당 주문 수" value={limits.data ? `서버 검증 · ${limits.data.maxHourlyOrders}건` : "서버 검증"} ok />
              <Text style={[font.caption, { color: c.muted }]}>집중도는 {firm} 잔고로 미리 계산했어요. 실전 판단에는 모의 데이터를 쓰지 않아요.</Text>
            </View>
          </ScrollView>

          <View style={[s.footer, { paddingBottom: Math.max(insets.bottom, 16) + 8 }]}>
            {outcome?.kind === "rejected" && <Text accessibilityRole="alert" style={[font.footnote, { color: c.danger }]}>{outcome.message}</Text>}
            {concOver && <Text style={[font.footnote, { color: c.danger }]}>이 주문 뒤 종목 비중이 한도를 넘어요. 수량을 줄여 주세요.</Text>}
            {farFromPrice && deviation != null && (
              <Text style={[font.footnote, { color: c.caution }]}>지정가가 현재가보다 {Math.abs(deviation).toFixed(1)}% {deviation > 0 ? "높아요" : "낮아요"}. 가격을 다시 확인해 주세요.</Text>
            )}
            <View style={s.total}>
              <Text style={[font.subhead, { color: c.muted }]}>예상 금액 · 수수료·세금 별도</Text>
              <Text style={[font.subhead, tabular, s.bold, { color: c.text }]}>{valid ? `${formatWon(unitPrice * quantity)}원` : "—"}</Text>
            </View>
            <HoldToConfirm
              label={isLimit ? `길게 눌러 ${formatWon(unitPrice)}원에 ${formatWon(quantity)}주 ${verb}` : `길게 눌러 시장가로 ${formatWon(quantity)}주 ${verb}`}
              accessibilityLabel={`${firm} 실전 ${verb}, ${formatWon(quantity)}주${isLimit ? `, ${formatWon(unitPrice)}원` : ", 시장가"}`}
              color={sideColor}
              fg={sideFg}
              disabled={blocked}
              busy={busy}
              onConfirm={submit}
            />
          </View>
        </>
      )}
    </KeyboardAvoidingView>
  );
}

function RiskRow({ label, value, ok }: { label: string; value: string; ok: boolean }) {
  const { c } = useTheme();
  return (
    <View style={s.riskRow}>
      <View style={s.riskLabel}>
        <Icon name={ok ? "check" : "alert"} size={15} color={ok ? c.positive : c.danger} strokeWidth={2.4} />
        <Text style={[font.subhead, { color: c.body }]}>{label}</Text>
      </View>
      <Text style={[font.subhead, tabular, { color: ok ? c.text : c.danger }]}>{value}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  flex: { flex: 1 },
  center: { textAlign: "center" },
  semibold: { fontWeight: "600" },
  bold: { fontWeight: "700" },
  header: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingHorizontal: 16, paddingBottom: 4 },
  headerTitle: { alignItems: "center", flex: 1 },
  badge: { width: 44, alignItems: "center" },
  body: { padding: 16, gap: 14 },
  warning: { flexDirection: "row", alignItems: "center", gap: 10, padding: 12, borderRadius: 18, borderWidth: 1 },
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
  riskRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  riskLabel: { flexDirection: "row", alignItems: "center", gap: 8 },
  footer: { paddingHorizontal: 16, paddingTop: 12, gap: 8 },
  total: { flexDirection: "row", justifyContent: "space-between", paddingHorizontal: 4 },
  pending: { padding: 12, borderRadius: 18, borderWidth: 1, gap: 8 },
  pendingActions: { flexDirection: "row", justifyContent: "space-between" },
  done: { flex: 1, alignItems: "center", justifyContent: "center", padding: 32, gap: 12 },
  doneButton: { alignSelf: "stretch" },
});
