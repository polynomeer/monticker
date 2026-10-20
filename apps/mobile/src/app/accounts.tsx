import { useCallback, useEffect, useState } from "react";
import { View, Text, ScrollView, StyleSheet, ActivityIndicator, TouchableOpacity, RefreshControl, Linking } from "react-native";
import { useRouter } from "expo-router";
import type { HoldingEvent, HoldingEventFeed, PortfolioAccount, PortfolioHolding, PortfolioHoldings } from "@monticker/types";
import { authFetch } from "@/services/auth";
import { getWebBase } from "@/services/api";
import { useAuthState } from "@/services/useAuth";
import { SignInRequired } from "@/components/SignInRequired";

/**
 * 내 계좌(ADR-106 1단계, docs/account-aggregation-plan.md §8) — 손 입력·CSV로 넣은 모든 실계좌의 합친 보유와
 * 보유 종목 소식(ADR-108). 읽기 전용이다: 계좌 추가·파일 가져오기·동의는 웹 "내 자산"에서 한다(파일 고르기·동의 전문).
 */
export default function AccountsScreen() {
  const auth = useAuthState();
  const router = useRouter();
  const [accounts, setAccounts] = useState<PortfolioAccount[] | null>(null);
  const [holdings, setHoldings] = useState<PortfolioHoldings | null>(null);
  const [events, setEvents] = useState<HoldingEvent[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error" | "unavailable">("loading");
  const [refreshing, setRefreshing] = useState(false);
  const [moreLoading, setMoreLoading] = useState(false);

  const load = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    try {
      const [a, h, e] = await Promise.all([
        authFetch("/api/portfolio/accounts"),
        authFetch("/api/portfolio/holdings?scope=REAL"),
        authFetch("/api/portfolio/events?limit=20"),
      ]);
      // 서버에 아직 이 기능이 없다(배포 전) — 오류가 아니라 준비 중으로 보인다
      if (a.status === 404) { setState("unavailable"); return; }
      if (!a.ok || !h.ok || !e.ok) { setState("error"); return; }
      const feed: HoldingEventFeed = await e.json();
      setAccounts(await a.json());
      setHoldings(await h.json());
      setEvents(feed.events);
      setCursor(feed.nextCursor);
      setState("ready");
    } catch {
      setState("error");
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { if (auth === "signedIn") load(); }, [auth, load]);

  const loadMore = async () => {
    if (!cursor || moreLoading) return;
    setMoreLoading(true);
    try {
      const res = await authFetch(`/api/portfolio/events?limit=20&cursor=${encodeURIComponent(cursor)}`);
      if (res.ok) {
        const feed: HoldingEventFeed = await res.json();
        setEvents((prev) => [...prev, ...feed.events]);
        setCursor(feed.nextCursor);
      }
    } finally {
      setMoreLoading(false);
    }
  };

  const openWeb = () => Linking.openURL(`${getWebBase()}/assets`);

  if (auth === "signedOut") return <SignInRequired message="여러 증권 계좌를 한곳에서 보려면 로그인해 주세요" />;
  if (auth === "unknown" || state === "loading") return <View style={s.center}><ActivityIndicator size="large" color={ACCENT} /></View>;
  if (state === "unavailable") return <Empty icon="🛠️" title="아직 준비 중이에요" sub="곧 여러 증권 계좌를 한곳에서 볼 수 있어요" />;
  if (state === "error") {
    return <Empty icon="⚠️" title="불러오지 못했어요" sub="잠시 후 다시 시도해 주세요" action={{ label: "다시 시도", onPress: () => load() }} />;
  }
  if (!accounts || accounts.length === 0) {
    return (
      <Empty
        icon="💼"
        title="연결된 계좌가 없어요"
        sub="웹의 「내 자산」에서 증권사 거래내역 파일을 올리거나 거래를 직접 적으면 여기에 모여요. 증권사 아이디·비밀번호는 받지 않아요."
        action={{ label: "웹에서 계좌 추가", onPress: openWeb }}
      />
    );
  }

  return (
    <ScrollView
      style={s.container}
      contentContainerStyle={s.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => load(true)} tintColor={ACCENT} />}
    >
      {holdings?.totals.map((t) => (
        <View key={t.currency} style={s.card}>
          <Text style={s.cardLabel}>{t.currency} 자산 · 계좌 {accounts.length}개</Text>
          <Text style={s.cardValue}>{money(t.marketValue, t.currency)}</Text>
          <Text style={[s.cardSub, dir(t.unrealizedPnl)]}>
            평가손익 {signed(t.unrealizedPnl, t.currency)}
            {t.costBasis > 0 && t.unpricedCount === 0 ? `  (${pct((t.unrealizedPnl / t.costBasis) * 100)})` : ""}
          </Text>
          {t.unpricedCount > 0 && <Text style={s.note}>시세가 없는 {t.unpricedCount}종목은 평가금액에서 빠졌어요</Text>}
        </View>
      ))}

      <Text style={s.section}>보유 종목</Text>
      {holdings && holdings.holdings.length === 0 ? (
        <Text style={s.muted}>보유 종목이 없어요</Text>
      ) : (
        holdings?.holdings.map((h) => <HoldingRow key={`${h.stockId ?? h.instrumentCode}-${h.currency}`} h={h} onPress={h.symbol && h.stockId ? () => router.push(`/stocks/${h.symbol}`) : undefined} />)
      )}
      <Text style={s.note}>평균단가는 이동평균(매수 수수료 포함)이라 증권사 앱과 다를 수 있어요.</Text>

      <Text style={s.section}>보유 종목 소식</Text>
      {events.length === 0 ? (
        <Text style={s.muted}>아직 소식이 없어요</Text>
      ) : (
        events.map((e) => (
          <TouchableOpacity key={e.id} style={s.event} onPress={e.symbol ? () => router.push(`/stocks/${e.symbol}`) : undefined}>
            <View style={s.eventTop}>
              <Text style={[s.eventType, { color: EVENT_COLOR[e.eventType] ?? MUTED }]}>{EVENT_LABEL[e.eventType] ?? e.eventType}</Text>
              <Text style={s.eventStock}>{e.name ?? e.symbol}</Text>
              <Text style={s.eventTime}>{timeAgo(e.eventTime)}</Text>
            </View>
            <Text style={s.eventTitle}>{e.title}</Text>
          </TouchableOpacity>
        ))
      )}
      {cursor && (
        <TouchableOpacity style={s.more} onPress={loadMore} disabled={moreLoading}>
          {moreLoading ? <ActivityIndicator color={ACCENT} /> : <Text style={s.moreText}>더 보기</Text>}
        </TouchableOpacity>
      )}

      <TouchableOpacity style={s.webLink} onPress={openWeb}>
        <Text style={s.webLinkText}>계좌 추가·파일 가져오기는 웹 「내 자산」에서</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

function HoldingRow({ h, onPress }: { h: PortfolioHolding; onPress?: () => void }) {
  return (
    <TouchableOpacity style={s.row} onPress={onPress} disabled={!onPress}>
      <View style={s.rowLeft}>
        <View style={s.rowTitle}>
          <Text style={s.name} numberOfLines={1}>{h.name ?? h.symbol ?? h.instrumentCode}</Text>
          {h.needsReview && <Text style={s.badge}>확인 필요</Text>}
        </View>
        <Text style={s.sub}>{qty(h.quantity)}주 · 평균 {money(h.avgCost, h.currency)}</Text>
      </View>
      <View style={s.rowRight}>
        <Text style={s.value}>{money(h.marketValue, h.currency)}</Text>
        <Text style={[s.sub, dir(h.unrealizedPnl)]}>{h.unrealizedPnlRate == null ? "시세 없음" : pct(h.unrealizedPnlRate)}</Text>
      </View>
    </TouchableOpacity>
  );
}

function Empty({ icon, title, sub, action }: { icon: string; title: string; sub: string; action?: { label: string; onPress: () => void } }) {
  return (
    <View style={s.center}>
      <Text style={s.emptyIcon}>{icon}</Text>
      <Text style={s.emptyTitle}>{title}</Text>
      <Text style={s.emptySub}>{sub}</Text>
      {action && (
        <TouchableOpacity style={s.cta} onPress={action.onPress} accessibilityRole="button">
          <Text style={s.ctaText}>{action.label}</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등", PRICE_DROP: "급락", VOLUME_SURGE: "거래량", NEWS_PUBLISHED: "뉴스", DISCLOSURE_PUBLISHED: "공시",
};
const EVENT_COLOR: Record<string, string> = {
  PRICE_SPIKE: "#ff5370", PRICE_DROP: "#4fc3f7", VOLUME_SURGE: "#5c7cfa", DISCLOSURE_PUBLISHED: "#c792ea", NEWS_PUBLISHED: "#69db7c",
};

function money(v: number | null | undefined, currency: string) {
  if (v == null) return "—";
  const d = currency === "KRW" ? 0 : 2;
  const s = v.toLocaleString("ko-KR", { minimumFractionDigits: d, maximumFractionDigits: d });
  return currency === "KRW" ? `${s}원` : `${s} ${currency}`;
}
const signed = (v: number, c: string) => `${v > 0 ? "+" : ""}${money(v, c)}`;
const pct = (v: number) => `${v > 0 ? "+" : ""}${v.toFixed(2)}%`;
const qty = (v: number) => v.toLocaleString("ko-KR", { maximumFractionDigits: 6 });
const dir = (v: number | null | undefined) => (v == null || v === 0 ? null : v > 0 ? s.pos : s.neg);

function timeAgo(iso: string) {
  const m = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  if (m < 60 * 24) return `${Math.floor(m / 60)}시간 전`;
  return `${Math.floor(m / (60 * 24))}일 전`;
}

const BG = "#0d1117";
const SURF = "#161929";
const BORDER = "#242840";
const TEXT = "#e8eaf6";
const MUTED = "#5c6288";
const ACCENT = "#5c7cfa";

const s = StyleSheet.create({
  container:  { flex: 1, backgroundColor: BG },
  content:    { padding: 16, paddingBottom: 40 },
  center:     { flex: 1, alignItems: "center", justifyContent: "center", padding: 32, backgroundColor: BG },
  card:       { backgroundColor: SURF, borderColor: BORDER, borderWidth: 1, borderRadius: 14, padding: 16, marginBottom: 10 },
  cardLabel:  { color: MUTED, fontSize: 12 },
  cardValue:  { color: TEXT, fontSize: 24, fontWeight: "700", marginTop: 4, fontVariant: ["tabular-nums"] },
  cardSub:    { color: TEXT, fontSize: 13, marginTop: 4, fontVariant: ["tabular-nums"] },
  note:       { color: MUTED, fontSize: 11, marginTop: 6 },
  section:    { color: TEXT, fontSize: 15, fontWeight: "700", marginTop: 20, marginBottom: 6 },
  muted:      { color: MUTED, fontSize: 13, paddingVertical: 12 },
  row:        { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: BORDER },
  rowLeft:    { flex: 1, paddingRight: 12 },
  rowTitle:   { flexDirection: "row", alignItems: "center", gap: 6 },
  rowRight:   { alignItems: "flex-end" },
  name:       { color: TEXT, fontSize: 15, fontWeight: "600", flexShrink: 1 },
  badge:      { color: "#ffb86c", fontSize: 10, borderColor: "#ffb86c", borderWidth: 1, borderRadius: 4, paddingHorizontal: 4, overflow: "hidden" },
  sub:        { color: MUTED, fontSize: 12, marginTop: 2, fontVariant: ["tabular-nums"] },
  value:      { color: TEXT, fontSize: 15, fontWeight: "600", fontVariant: ["tabular-nums"] },
  pos:        { color: "#ff5370" },
  neg:        { color: "#4fc3f7" },
  event:      { paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: BORDER },
  eventTop:   { flexDirection: "row", alignItems: "center", gap: 8 },
  eventType:  { fontSize: 11, fontWeight: "700" },
  eventStock: { color: TEXT, fontSize: 12, fontWeight: "600" },
  eventTime:  { color: MUTED, fontSize: 11, marginLeft: "auto" },
  eventTitle: { color: TEXT, fontSize: 14, marginTop: 4 },
  more:       { alignItems: "center", paddingVertical: 14 },
  moreText:   { color: ACCENT, fontSize: 13, fontWeight: "600" },
  webLink:    { alignItems: "center", paddingVertical: 20 },
  webLinkText:{ color: MUTED, fontSize: 12 },
  emptyIcon:  { fontSize: 40, marginBottom: 12 },
  emptyTitle: { color: TEXT, fontSize: 17, fontWeight: "700", marginBottom: 6 },
  emptySub:   { color: MUTED, fontSize: 13, textAlign: "center", marginBottom: 24, lineHeight: 19 },
  cta:        { backgroundColor: ACCENT, paddingHorizontal: 24, paddingVertical: 12, borderRadius: 24 },
  ctaText:    { color: "#fff", fontWeight: "700", fontSize: 14 },
});
