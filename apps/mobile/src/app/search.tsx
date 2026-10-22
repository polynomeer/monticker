import { useEffect, useMemo, useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import type { ScreenerQuote, StockEvent, StockSearchItem } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius, tabular } from "@/theme/type";
import { useApi } from "@/services/useApi";
import { useAuthState } from "@/services/useAuth";
import { rememberQuery, useRecent } from "@/services/recent";
import { Badge, ChangeText, Chip, Glass, GroupedList, Icon, SectionHeader } from "@/components/ui";
import { LinearGradientFade } from "@/components/shell/Fade";
import { EVENT_LABEL, eventTone } from "@/lib/events";
import { feedTime } from "@/lib/feed";
import { formatPrice } from "@/lib/format";

type Scope = "all" | "stock" | "event" | "strategy" | "news";
const SCOPES: { value: Scope; label: string }[] = [
  { value: "all", label: "전체" }, { value: "stock", label: "종목" }, { value: "event", label: "이벤트" },
  { value: "strategy", label: "전략" }, { value: "news", label: "뉴스" },
];

interface NewsResult { id: number; stockId: number | null; title: string; source: string | null; publishedAt: string }
interface StrategyResult { id: number; name: string; subscribe_count: number | null; author_nickname?: string | null }

/** 결과가 배열이거나 { items | content } 페이지다 */
function rows<T>(d: unknown): T[] {
  if (Array.isArray(d)) return d as T[];
  const o = d as { items?: T[]; content?: T[] } | null;
  return o?.items ?? o?.content ?? [];
}

function useDebounced(v: string, ms = 250) {
  const [d, setD] = useState(v);
  useEffect(() => { const t = setTimeout(() => setD(v), ms); return () => clearTimeout(t); }, [v, ms]);
  return d;
}

/**
 * 검색(ADR-113) — 탭이 아니라 어디서나 여는 화면. 아래 바가 홈 버튼 + 입력창으로 바뀌고 키보드가 바로 뜬다.
 * 범위별로 기존 검색 API를 함께 부르고(통합 검색 API 없음) 개수를 칩에 붙인다.
 */
export default function SearchScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const signedIn = useAuthState() === "signedIn";
  const [query, setQuery] = useState("");
  const [scope, setScope] = useState<Scope>("all");
  const recent = useRecent();
  const q = useDebounced(query.trim());
  const enc = encodeURIComponent(q);
  const wants = (s: Scope) => !!q && (scope === "all" || scope === s);

  const stocks = useApi<StockSearchItem[]>(wants("stock") ? `/api/stocks/search?query=${enc}` : null);
  const events = useApi<StockEvent[]>(wants("event") ? `/api/events/search?query=${enc}&limit=12` : null);
  const news = useApi<unknown>(signedIn && wants("news") ? `/api/news/search?query=${enc}&limit=10` : null, { auth: true });
  const strategies = useApi<unknown>(signedIn && wants("strategy") ? `/api/quant/market/search?q=${encodeURIComponent(q.slice(0, 50))}&page=0&size=8` : null, { auth: true });
  const popular = useApi<{ items: ScreenerQuote[] }>(q ? null : "/api/screener?tab=realtime&market=all&sort=amount&limit=5");

  const stockRows = (stocks.data ?? []).slice(0, 20);
  const eventRows = events.data ?? [];
  const newsRows = rows<NewsResult>(news.data);
  const strategyRows = rows<StrategyResult>(strategies.data);
  const quoteIds = [...new Set([...stockRows.map((s) => s.id), ...eventRows.map((e) => e.stockId)])].slice(0, 50);
  const quotes = useApi<{ items: ScreenerQuote[] }>(quoteIds.length ? `/api/screener/quotes?ids=${quoteIds.join(",")}` : null);
  const quoteById = useMemo(() => new Map((quotes.data?.items ?? []).map((x) => [x.stockId, x])), [quotes.data]);

  const counts: Record<Scope, number> = {
    all: stockRows.length + eventRows.length + strategyRows.length + newsRows.length,
    stock: stockRows.length, event: eventRows.length, strategy: strategyRows.length, news: newsRows.length,
  };
  const limit = (s: Scope) => (scope === "all" ? 3 : 50);
  const now = new Date();

  const openStock = (symbol: string) => {
    rememberQuery(q);
    router.push(`/stocks/${symbol}`);
  };

  return (
    <KeyboardAvoidingView style={[s.fill, { backgroundColor: c.bg }]} behavior={Platform.OS === "ios" ? "padding" : undefined}>
      <ScrollView contentContainerStyle={[s.content, { paddingTop: insets.top + 54, paddingBottom: 120 + insets.bottom }]} keyboardShouldPersistTaps="handled">
        <Text style={[font.largeTitle, { color: c.text }]} accessibilityRole="header">검색</Text>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={s.chips} style={s.chipScroll} accessibilityLabel="검색 범위">
          {SCOPES.map((o) => (
            <Chip key={o.value} label={q ? `${o.label} ${counts[o.value]}` : o.label} selected={scope === o.value} onPress={() => setScope(o.value)} />
          ))}
        </ScrollView>

        {!q ? (
          <>
            {recent.queries.length > 0 && (
              <View style={s.section}>
                <SectionHeader title="최근 검색어" />
                <View style={s.wrap}>
                  {recent.queries.map((t) => (
                    <Pressable key={t} onPress={() => setQuery(t)} accessibilityRole="button" style={[s.recentChip, { backgroundColor: c.card }]}>
                      <Icon name="clock" size={14} color={c.muted} strokeWidth={2.4} />
                      <Text style={[font.subhead, { color: c.body }]}>{t}</Text>
                    </Pressable>
                  ))}
                </View>
              </View>
            )}

            {recent.stocks.length > 0 && (
              <View style={s.section}>
                <SectionHeader
                  title="최근 본 종목"
                  action={<Text onPress={recent.clearStocks} accessibilityRole="button" style={[font.subhead, { color: c.accent }]}>지우기</Text>}
                />
                <View style={s.grid}>
                  {recent.stocks.slice(0, 3).map((st) => (
                    <Pressable key={st.symbol} onPress={() => router.push(`/stocks/${st.symbol}`)} accessibilityRole="button" style={[s.recentStock, { backgroundColor: c.card }]}>
                      <Text style={[font.subhead, s.semibold, { color: c.text }]} numberOfLines={1}>{st.name}</Text>
                      <Text style={[font.footnote, { color: c.muted }]}>{st.symbol}</Text>
                    </Pressable>
                  ))}
                </View>
              </View>
            )}

            <View style={s.section}>
              <SectionHeader title="많이 찾는 종목" sub="검색 통계를 모으기 전이라 지금은 거래대금 상위 종목입니다." />
              <GroupedList inset={48}>
                {(popular.data?.items ?? []).map((p, i) => (
                  <Pressable key={p.stockId} onPress={() => openStock(p.symbol)} accessibilityRole="button" style={({ pressed }) => [s.rankRow, pressed && { backgroundColor: c.raised }]}>
                    <Text style={[font.headline, s.rank, { color: c.accent }]}>{i + 1}</Text>
                    <Text style={[font.body, s.fill, { color: c.text }]} numberOfLines={1}>{p.name}</Text>
                    <ChangeText value={p.changeRate} />
                  </Pressable>
                ))}
              </GroupedList>
            </View>
          </>
        ) : (
          <>
            {wants("stock") && stockRows.length > 0 && (
              <View style={s.section}>
                <SectionHeader title="종목" action={scope === "all" && stockRows.length > 3 ? <SeeAll onPress={() => setScope("stock")} /> : undefined} />
                <GroupedList inset={64}>
                  {stockRows.slice(0, limit("stock")).map((st) => {
                    const qt = quoteById.get(st.id);
                    return (
                      <Pressable key={st.id} onPress={() => openStock(st.symbol)} accessibilityRole="button" style={({ pressed }) => [s.resultRow, pressed && { backgroundColor: c.raised }]}>
                        <View style={[s.avatar, { backgroundColor: c.raised }]}><Text style={[font.subhead, s.semibold, { color: c.text }]}>{st.name.slice(0, 1)}</Text></View>
                        <View style={s.fill}>
                          <Text style={[font.headline, { color: c.text }]} numberOfLines={1}>{st.name}</Text>
                          <Text style={[font.footnote, { color: c.muted }]}>{st.symbol} · {st.market}</Text>
                        </View>
                        <View style={s.right}>
                          <Text style={[font.subhead, tabular, s.semibold, { color: c.text }]}>{qt ? formatPrice(qt.price) : "—"}</Text>
                          {qt ? <ChangeText value={qt.changeRate} size="footnote" /> : <Text style={[font.footnote, { color: c.muted }]}>—</Text>}
                        </View>
                      </Pressable>
                    );
                  })}
                </GroupedList>
              </View>
            )}

            {wants("event") && eventRows.length > 0 && (
              <View style={s.section}>
                <SectionHeader title="이벤트" action={scope === "all" && eventRows.length > 3 ? <SeeAll onPress={() => setScope("event")} /> : undefined} />
                <GroupedList>
                  {eventRows.slice(0, limit("event")).map((e) => {
                    const qt = quoteById.get(e.stockId);
                    return (
                      <Pressable key={e.id} disabled={!qt} onPress={() => qt && openStock(qt.symbol)} accessibilityRole="button" style={({ pressed }) => [s.eventRow, pressed && { backgroundColor: c.raised }]}>
                        <Badge label={EVENT_LABEL[e.eventType] ?? "이벤트"} tone={eventTone(e.eventType)} />
                        <View style={s.fill}>
                          <Text style={[font.subhead, { color: c.text }]} numberOfLines={2}>{qt ? `${qt.name} · ` : ""}{e.title}</Text>
                          <Text style={[font.footnote, { color: c.muted }]}>{feedTime(e.eventTime, now)}</Text>
                        </View>
                      </Pressable>
                    );
                  })}
                </GroupedList>
              </View>
            )}

            {wants("strategy") && strategyRows.length > 0 && (
              <View style={s.section}>
                <SectionHeader title="전략" />
                <GroupedList>
                  {strategyRows.slice(0, limit("strategy")).map((st) => (
                    <View key={st.id} style={s.eventRow}>
                      <Icon name="flask" size={18} color={c.accent} />
                      <View style={s.fill}>
                        <Text style={[font.subhead, { color: c.text }]} numberOfLines={1}>{st.name}</Text>
                        <Text style={[font.footnote, { color: c.muted }]}>전략 마켓 · 구독 {st.subscribe_count ?? 0}명</Text>
                      </View>
                    </View>
                  ))}
                </GroupedList>
              </View>
            )}

            {wants("news") && newsRows.length > 0 && (
              <View style={s.section}>
                <SectionHeader title="뉴스" />
                <GroupedList>
                  {newsRows.slice(0, limit("news")).map((n) => (
                    <View key={n.id} style={s.eventRow}>
                      <View style={s.fill}>
                        <Text style={[font.subhead, { color: c.text }]} numberOfLines={2}>{n.title}</Text>
                        <Text style={[font.footnote, { color: c.muted }]}>{[n.source, feedTime(n.publishedAt, now)].filter(Boolean).join(" · ")}</Text>
                      </View>
                    </View>
                  ))}
                </GroupedList>
              </View>
            )}

            {counts.all === 0 && !stocks.loading && !events.loading && (
              <Text style={[font.subhead, s.none, { color: c.muted }]}>
                “{q}”에 맞는 결과가 없어요.{!signedIn ? " 전략·뉴스는 로그인하면 함께 찾아요." : ""}
              </Text>
            )}
          </>
        )}
      </ScrollView>

      <View style={[s.bottom, { paddingBottom: Math.max(insets.bottom, 16) + 14 }]} pointerEvents="box-none">
        <LinearGradientFade color={c.bg} height={140} />
        <View style={s.bar}>
          <Glass style={s.home}>
            <Pressable onPress={() => (router.canGoBack() ? router.back() : router.replace("/"))} accessibilityRole="button" accessibilityLabel="탭 바 펼치기 (홈)" style={s.hit}>
              <Icon name="home" size={24} color={c.soft} strokeWidth={1.9} />
            </Pressable>
          </Glass>
          <Glass style={s.inputWrap}>
            <View style={s.inputRow}>
              <Icon name="search" size={20} color={c.soft} strokeWidth={2.2} />
              <TextInput
                value={query}
                onChangeText={setQuery}
                onSubmitEditing={() => rememberQuery(query)}
                autoFocus
                placeholder="종목·이벤트·전략 검색"
                placeholderTextColor={c.muted}
                returnKeyType="search"
                accessibilityLabel="검색"
                autoCorrect={false}
                style={[font.body, s.input, { color: c.text }]}
              />
              {query.length > 0 && (
                <Pressable onPress={() => setQuery("")} accessibilityRole="button" accessibilityLabel="검색어 지우기" hitSlop={8}>
                  <Icon name="close" size={18} color={c.muted} />
                </Pressable>
              )}
            </View>
          </Glass>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

function SeeAll({ onPress }: { onPress: () => void }) {
  const { c } = useTheme();
  return <Text onPress={onPress} accessibilityRole="button" style={[font.subhead, { color: c.accent }]}>모두 보기</Text>;
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  content: { paddingHorizontal: 16, gap: 16 },
  chipScroll: { marginRight: -16 },
  chips: { gap: 8, paddingRight: 16 },
  section: { gap: 8 },
  wrap: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  recentChip: { height: 36, paddingHorizontal: 14, borderRadius: 18, flexDirection: "row", alignItems: "center", gap: 6 },
  grid: { flexDirection: "row", gap: 8 },
  recentStock: { flex: 1, paddingVertical: 12, paddingHorizontal: 14, borderRadius: radius.tile, gap: 2 },
  semibold: { fontWeight: "600" },
  rankRow: { flexDirection: "row", alignItems: "center", gap: 14, height: 52, paddingHorizontal: 16 },
  rank: { width: 18, fontWeight: "700" },
  resultRow: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  avatar: { width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center" },
  right: { alignItems: "flex-end", gap: 2 },
  eventRow: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 12, paddingHorizontal: 16 },
  none: { textAlign: "center", paddingVertical: 24 },
  bottom: { position: "absolute", left: 0, right: 0, bottom: 0, paddingHorizontal: 20 },
  bar: { flexDirection: "row", alignItems: "center", gap: 10 },
  home: { width: 56, height: 56, borderRadius: 28 },
  hit: { flex: 1, alignItems: "center", justifyContent: "center" },
  inputWrap: { flex: 1, height: 56, borderRadius: 28 },
  inputRow: { flex: 1, flexDirection: "row", alignItems: "center", gap: 10, paddingHorizontal: 18 },
  input: { flex: 1, minWidth: 0, paddingVertical: 0 },
});
