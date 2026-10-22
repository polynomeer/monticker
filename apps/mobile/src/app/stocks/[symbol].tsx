import { useEffect, useMemo, useState } from "react";
import { Pressable, Share, StyleSheet, Text, View } from "react-native";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import type { PaperPortfolio, StockEvent, StockPriceResponse, StockSearchItem, WatchlistGroup } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { changeColor } from "@/theme/palette";
import { font, tabular } from "@/theme/type";
import { authFetch } from "@/services/auth";
import { useApi } from "@/services/useApi";
import { useAuthState } from "@/services/useAuth";
import { useTradingMode } from "@/services/mode";
import { rememberStock } from "@/services/recent";
import { Glass, GroupedList, Icon, Loading, EmptyState, Screen, SectionHeader, Segmented, withAlpha } from "@/components/ui";
import { BackButton } from "@/components/ui/BackButton";
import { LinearGradientFade } from "@/components/shell/Fade";
import { PriceChart } from "@/components/stock/PriceChart";
import { candleQuery, PERIODS, type Period } from "@/lib/chart";
import { EVENT_LABEL } from "@/lib/events";
import { feedTime } from "@/lib/feed";
import { formatPct, formatSignedWon, formatWon } from "@/lib/format";

interface Candle { time: number; close: number }

/**
 * 종목 상세(ADR-113) — 시세, 기간 차트 + MA20, "왜 움직였나", 아래 고정 보유 요약 + 매도·매수(지금 모드로만 주문).
 * 상태 바·탭 바는 숨긴다.
 */
export default function StockDetailScreen() {
  const { symbol } = useLocalSearchParams<{ symbol: string }>();
  const { c, market } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const auth = useAuthState();
  const signedIn = auth === "signedIn";
  const { mode } = useTradingMode();
  const [period, setPeriod] = useState<Period>("1d");

  const search = useApi<StockSearchItem[]>(symbol ? `/api/stocks/search?query=${encodeURIComponent(symbol)}` : null);
  const stock = search.data?.find((s) => s.symbol === symbol) ?? null;
  const id = stock?.id;
  useEffect(() => { if (stock) rememberStock({ symbol: stock.symbol, name: stock.name }); }, [stock]);

  const price = useApi<StockPriceResponse>(id ? `/api/stocks/${id}/price` : null, { refreshMs: 10_000 });
  const candles = useApi<Candle[]>(id ? `/api/stocks/${id}/candles?${candleQuery(period, new Date())}` : null);
  const weekAgo = useMemo(() => new Date(Date.now() - 7 * 86_400_000).toISOString(), []);
  const events = useApi<StockEvent[]>(id ? `/api/stocks/${id}/events?from=${encodeURIComponent(weekAgo)}&limit=5` : null, { refreshMs: 60_000 });
  const portfolio = useApi<PaperPortfolio>(signedIn ? "/api/paper/portfolio" : null, { auth: true });
  const groups = useApi<WatchlistGroup[]>(signedIn ? "/api/watchlists" : null, { auth: true });

  if (search.loading) return <Loading />;
  if (!stock) return <EmptyState title="종목을 찾지 못했어요" sub={symbol} action={{ label: "검색으로", onPress: () => router.replace("/search") }} />;

  const p = price.data;
  const last = p?.price ?? null;
  const rate = p?.changeRate ?? null;
  const diff = last != null && p?.prevClose ? last - p.prevClose : null;
  const closes = (candles.data ?? []).map((k) => Number(k.close));
  const times = (candles.data ?? []).map((k) => k.time);
  const holding = portfolio.data?.holdings.find((h) => h.stockId === id && h.quantity > 0) ?? null;
  const watchItem = (groups.data ?? []).flatMap((g) => g.items).find((i) => i.stockId === id) ?? null;
  const evs = events.data ?? [];
  const now = new Date();
  const live = mode === "live";

  const toggleWatch = async () => {
    if (!signedIn) { router.push("/login"); return; }
    if (watchItem) {
      await authFetch(`/api/watchlists/items/${watchItem.id}`, { method: "DELETE" });
    } else {
      let groupId = groups.data?.[0]?.id;
      if (groupId == null) {
        const r = await authFetch("/api/watchlists/groups", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name: "관심종목" }) });
        groupId = r.ok ? ((await r.json()) as { id: number }).id : undefined;
      }
      if (groupId != null) {
        await authFetch(`/api/watchlists/groups/${groupId}/items`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ stockId: id }) });
      }
    }
    groups.reload();
  };

  const order = (side: "BUY" | "SELL") => {
    if (!signedIn) { router.push("/login"); return; }
    router.push({ pathname: "/order", params: { symbol: stock.symbol, side } });
  };

  return (
    <View style={[s.fill, { backgroundColor: c.bg }]}>
      <Screen
        leading={<BackButton />}
        bottomSpace={150}
        onRefresh={() => { price.reload(); candles.reload(); events.reload(); }}
        actions={
          <Glass style={s.toolbar}>
            <View style={s.toolbarRow}>
              <Pressable onPress={toggleWatch} accessibilityRole="button" accessibilityLabel={watchItem ? "관심종목에서 빼기" : "관심종목에 더하기"} style={s.toolbarButton}>
                <Icon name="star" size={20} color={watchItem ? c.caution : c.text} filled={!!watchItem} />
              </Pressable>
              <Pressable onPress={() => router.push("/alerts")} accessibilityRole="button" accessibilityLabel="가격 알림" style={s.toolbarButton}>
                <Icon name="bell" size={20} color={c.text} />
              </Pressable>
              <Pressable onPress={() => Share.share({ message: `${stock.name}(${stock.symbol}) · monticker` })} accessibilityRole="button" accessibilityLabel="공유" style={s.toolbarButton}>
                <Icon name="share" size={20} color={c.text} />
              </Pressable>
            </View>
          </Glass>
        }
      >
        <View style={s.quote}>
          <Text style={[font.subhead, { color: c.muted }]}>{stock.symbol} · {stock.market}</Text>
          <Text style={[s.name, { color: c.text }]} accessibilityRole="header">{stock.name}</Text>
          <View style={s.priceRow}>
            <Text style={[s.price, tabular, { color: c.text }]}>{last != null ? formatWon(last) : "—"}</Text>
            {rate != null && (
              <Text style={[font.headline, tabular, { color: changeColor(rate, market, c.muted) }]}>
                {diff != null ? `${formatSignedWon(diff)} ` : ""}({formatPct(rate)})
              </Text>
            )}
          </View>
          {p?.tradeTime && (
            <Text style={[font.footnote, { color: c.muted }]}>
              {feedTime(p.tradeTime, now)} 기준{p.volume != null ? ` · 거래량 ${(p.volume / 1e6).toFixed(1)}M주` : ""}
            </Text>
          )}
        </View>

        <Segmented<Period> compact accessibilityLabel="차트 기간" value={period} onChange={setPeriod} options={PERIODS} />
        <PriceChart closes={closes} times={times} up={(rate ?? 0) >= 0} eventTimes={evs.map((e) => Date.parse(e.eventTime) / 1000)} />

        <View style={s.section}>
          <SectionHeader title="왜 움직였나" />
          {evs.length === 0 ? (
            <Text style={[font.subhead, s.none, { color: c.muted }]}>최근 일주일 동안 큰 움직임이나 공시가 없었어요.</Text>
          ) : (
            <GroupedList inset={38}>
              {evs.map((e, i) => {
                const disclosure = e.eventType === "DISCLOSURE_PUBLISHED";
                const tone = e.eventType === "PRICE_SPIKE" ? market.up : e.eventType === "PRICE_DROP" ? market.down : disclosure ? c.disclosure : c.accent;
                const extra = e.volumeMultiple != null ? ` · 거래량 ${e.volumeMultiple.toFixed(1)}×` : "";
                return (
                  <View key={e.id} style={s.event}>
                    <View style={[s.eventDot, { backgroundColor: i === 0 ? c.accent : disclosure ? c.disclosure : withAlpha(tone, 0.8) }]} />
                    <View style={s.fill}>
                      <View style={s.eventHead}>
                        <Text style={[font.footnote, s.semibold, { color: tone }]}>{EVENT_LABEL[e.eventType] ?? "이벤트"}{extra}</Text>
                        <Text style={[font.footnote, { color: c.muted }]}>{feedTime(e.eventTime, now)}</Text>
                      </View>
                      <Text style={[font.subhead, s.eventText, { color: c.text }]}>{e.description ?? e.title}</Text>
                    </View>
                  </View>
                );
              })}
            </GroupedList>
          )}
        </View>
      </Screen>

      <View style={[s.bottom, { paddingBottom: Math.max(insets.bottom, 16) + 14 }]} pointerEvents="box-none">
        <LinearGradientFade color={c.bg} height={170} />
        {!live && holding && (
          <Glass style={s.holding}>
            <Text style={[font.footnote, s.holdingText, { color: c.soft }]}>
              보유 {formatWon(holding.quantity)}주 · 평균 {formatWon(holding.avgPrice)} ·{" "}
              <Text style={[s.semibold, { color: changeColor(holding.pnlRate, market, c.muted) }]}>{formatPct(holding.pnlRate)}</Text>
            </Text>
          </Glass>
        )}
        <View style={s.buttons}>
          <OrderButton label={`${live ? "" : "모의 "}매도`} color={market.down} fg={market.onDown} onPress={() => order("SELL")} />
          <OrderButton label={`${live ? "" : "모의 "}매수`} color={market.up} fg={market.onUp} onPress={() => order("BUY")} />
        </View>
      </View>
    </View>
  );
}

function OrderButton({ label, color, fg, onPress }: { label: string; color: string; fg: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} accessibilityRole="button" style={({ pressed }) => [s.orderButton, { backgroundColor: color, opacity: pressed ? 0.85 : 1 }]}>
      <Text style={[font.headline, { color: fg }]}>{label}</Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  toolbar: { height: 44, borderRadius: 22 },
  toolbarRow: { flexDirection: "row" },
  toolbarButton: { width: 48, height: 44, alignItems: "center", justifyContent: "center" },
  quote: { gap: 2 },
  name: { fontSize: 28, fontWeight: "700" },
  priceRow: { flexDirection: "row", alignItems: "baseline", gap: 10, marginTop: 2 },
  price: { fontSize: 34, fontWeight: "700", letterSpacing: -0.5 },
  section: { gap: 8 },
  none: { paddingHorizontal: 4 },
  event: { flexDirection: "row", gap: 12, paddingVertical: 14, paddingHorizontal: 16 },
  eventDot: { width: 10, height: 10, borderRadius: 5, marginTop: 6 },
  eventHead: { flexDirection: "row", justifyContent: "space-between" },
  eventText: { lineHeight: 21 },
  semibold: { fontWeight: "600" },
  bottom: { position: "absolute", left: 0, right: 0, bottom: 0, paddingHorizontal: 16, gap: 10 },
  holding: { alignSelf: "center", borderRadius: 16 },
  holdingText: { paddingVertical: 6, paddingHorizontal: 14 },
  buttons: { flexDirection: "row", gap: 10 },
  orderButton: { flex: 1, height: 54, borderRadius: 27, alignItems: "center", justifyContent: "center" },
});
