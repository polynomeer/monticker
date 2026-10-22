import { useMemo } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import type {
  BrokerageBalanceResponse, IntradaySeries, PaperPortfolio, QuantSignalFeedItem, RiskExposure, ScreenerQuote, StockEvent, WatchlistGroup,
} from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { useAuthState } from "@/services/useAuth";
import { useApi } from "@/services/useApi";
import { useTradingMode } from "@/services/mode";
import { useStocksBySymbols } from "@/services/stockLookup";
import { Button, Card, EmptyState, GlassButton, Icon, Screen, SectionLabel } from "@/components/ui";
import { MarketStrip } from "@/components/home/MarketStrip";
import { FeedCard } from "@/components/home/FeedCard";
import { buildFeed, countToday, dailyLossUsedPct, PRICE_EVENTS, type FeedStock } from "@/lib/feed";

/**
 * 홈(ADR-113) — 내 종목(관심·보유)의 새 소식, 전략 신호, 리스크 경고를 한 피드로. 서버에 개인화 피드가 없어 여기서 합친다.
 * 로그인 전에는 시장 전체의 최근 이벤트를 보여 준다.
 */
export default function HomeScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const auth = useAuthState();
  const signedIn = auth === "signedIn";
  // 보유는 지금 모드의 계좌 — 실전이면 증권사 잔고, 모의면 모의 포트폴리오(서로 섞지 않는다)
  const live = useTradingMode().mode === "live";

  const groups = useApi<WatchlistGroup[]>(signedIn ? "/api/watchlists" : null, { auth: true, refreshMs: 60_000 });
  const portfolio = useApi<PaperPortfolio>(signedIn && !live ? "/api/paper/portfolio" : null, { auth: true, refreshMs: 60_000 });
  const brokerBalance = useApi<BrokerageBalanceResponse>(signedIn && live ? "/api/brokerage/account/balance" : null, { auth: true, refreshMs: 60_000 });
  const liveStocks = useStocksBySymbols((brokerBalance.data?.holdings ?? []).filter((h) => Number(h.quantity) > 0).map((h) => h.symbol));
  const signals = useApi<QuantSignalFeedItem[]>(signedIn ? "/api/quant/signals/feed?limit=10" : null, { auth: true, refreshMs: 60_000 });
  // 일간 손실 경고는 모의 계좌 기준이라 실전 모드에서는 띄우지 않는다(ADR-025 — 실전 화면에 모의 숫자를 쓰지 않는다)
  const exposure = useApi<RiskExposure>(signedIn && !live ? "/api/risk/exposure" : null, { auth: true, refreshMs: 60_000 });

  const watchIds = useMemo(() => [...new Set((groups.data ?? []).flatMap((g) => g.items.map((i) => i.stockId)))], [groups.data]);
  const liveIdKey = [...liveStocks.values()].map((st) => st.id).join(",");
  const heldIds = useMemo(
    () => live
      ? new Set(liveIdKey ? liveIdKey.split(",").map(Number) : [])
      : new Set((portfolio.data?.holdings ?? []).filter((h) => h.quantity > 0).map((h) => h.stockId)),
    [live, liveIdKey, portfolio.data],
  );
  const myIds = useMemo(() => [...new Set([...heldIds, ...watchIds])].slice(0, 50), [heldIds, watchIds]);

  const latest = useApi<StockEvent[]>(signedIn && myIds.length ? `/api/events/latest?stockIds=${myIds.join(",")}` : null, { refreshMs: 30_000 });
  const recent = useApi<StockEvent[]>(signedIn ? null : "/api/events/recent?limit=10", { refreshMs: 30_000 });
  const events = (signedIn ? latest.data : recent.data) ?? [];
  const quoteIds = signedIn ? myIds : [...new Set(events.map((e) => e.stockId))].slice(0, 50);
  const quotes = useApi<{ items: ScreenerQuote[] }>(quoteIds.length ? `/api/screener/quotes?ids=${quoteIds.join(",")}` : null, { refreshMs: 30_000 });

  const now = new Date();
  const feed = useMemo(() => {
    const stocks = new Map<number, FeedStock>((quotes.data?.items ?? []).map((q) => [q.stockId, { symbol: q.symbol, name: q.name, changeRate: q.changeRate }]));
    const lim = exposure.data?.limits;
    return buildFeed({
      events,
      signals: signals.data ?? [],
      stocks,
      heldIds,
      riskUsedPct: exposure.data && lim ? dailyLossUsedPct(exposure.data.dailyPnlPct, lim.dailyLossLimitPct) : null,
      now,
    });
    // now는 렌더마다 바뀌어 넣지 않는다 — 데이터가 바뀔 때만 다시 합친다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events, signals.data, quotes.data, heldIds, exposure.data]);

  // 가격 이벤트 카드의 스파크라인(장중 종가)
  const sparkIds = feed.filter((f) => f.kind === "event" && PRICE_EVENTS.has(f.event.eventType)).map((f) => (f.kind === "event" ? f.event.stockId : 0)).slice(0, 10);
  const intraday = useApi<IntradaySeries[]>(sparkIds.length ? `/api/market/intraday?ids=${sparkIds.join(",")}` : null, { refreshMs: 60_000 });
  const closesById = new Map((intraday.data ?? []).map((s) => [s.stockId, s.closes.map(Number)]));

  const strategyCount = new Set((signals.data ?? []).map((s) => s.ruleSetId)).size;
  const today = countToday(feed, now);
  const subtitle = signedIn
    ? [`관심종목 ${watchIds.length}개`, heldIds.size ? `보유 ${heldIds.size}종목` : null, strategyCount ? `전략 ${strategyCount}개` : null]
        .filter(Boolean).join(" · ") + `에서 새 소식 ${today}건`
    : "시장에서 지금 움직이는 종목";

  const loading = signedIn ? groups.loading || latest.loading : recent.loading;
  const refresh = async () => {
    await Promise.all([groups.reload(), portfolio.reload(), brokerBalance.reload(), signals.reload(), exposure.reload(), latest.reload(), recent.reload(), quotes.reload()]);
  };

  return (
    <Screen
      title="홈"
      subtitle={subtitle}
      onRefresh={refresh}
      refreshing={false}
      actions={
        <GlassButton onPress={() => router.push("/settings")} accessibilityLabel="프로필·설정">
          <Icon name="user" size={20} color={c.text} />
        </GlassButton>
      }
    >
      <MarketStrip />
      <SectionLabel>{signedIn ? "내 종목 · 새 소식" : "시장 이벤트"}</SectionLabel>

      {feed.map((item, i) => (
        <FeedCard
          key={item.key}
          item={item}
          now={now}
          unread={i === 0 && item.kind === "event"}
          closes={item.kind === "event" ? closesById.get(item.event.stockId) : undefined}
        />
      ))}

      {!loading && feed.length === 0 && (
        signedIn
          ? <EmptyState fill={false} title="아직 새 소식이 없어요" sub="관심종목을 더하면 움직임과 공시를 이유와 함께 모아 드려요." action={{ label: "종목 찾기", onPress: () => router.push("/search") }} />
          : <EmptyState fill={false} title="시장 이벤트가 없어요" sub="장이 열리면 급등·급락과 거래량 급증이 여기에 보여요." />
      )}

      {!signedIn && auth !== "unknown" && (
        <Card style={s.signIn}>
          <Text style={[font.headline, { color: c.text }]}>내 종목 소식만 보려면</Text>
          <Text style={[font.subhead, { color: c.muted }]}>로그인하면 관심종목·보유 종목·구독 전략의 새 소식이 이 자리에 모여요.</Text>
          <View style={s.signInAction}><Button label="로그인" onPress={() => router.push("/login")} /></View>
        </Card>
      )}
    </Screen>
  );
}

const s = StyleSheet.create({
  signIn: { gap: 6 },
  signInAction: { marginTop: 8, alignSelf: "flex-start" },
});
