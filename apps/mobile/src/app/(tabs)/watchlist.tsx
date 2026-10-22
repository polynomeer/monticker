import { useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import type { AlertRule, IntradaySeries, ScreenerQuote, WatchlistGroup } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius, tabular } from "@/theme/type";
import { useAuthState } from "@/services/useAuth";
import { useApi } from "@/services/useApi";
import { Badge, ChangePill, Chip, EmptyState, Glass, GroupedList, Icon, Loading, Screen, Sparkline } from "@/components/ui";
import { SignInRequired } from "@/components/SignInRequired";
import { EVENT_LABEL, eventTone } from "@/lib/events";
import { formatPrice } from "@/lib/format";

type Sort = "group" | "change" | "name";
const SORT_LABEL: Record<Sort, string> = { group: "내가 정한 순서", change: "등락률 순", name: "이름 순" };
const NEXT_SORT: Record<Sort, Sort> = { group: "change", change: "name", name: "group" };
const BADGE_EVENTS = new Set(["PRICE_SPIKE", "PRICE_DROP", "VOLUME_SURGE", "DISCLOSURE_PUBLISHED"]);

/** 관심종목(ADR-113) — 그룹 칩, 요약 타일 3개, 시세·이벤트 배지·스파크라인 목록 */
export default function WatchlistScreen() {
  const { c, market } = useTheme();
  const router = useRouter();
  const auth = useAuthState();
  const signedIn = auth === "signedIn";
  const [groupId, setGroupId] = useState<number | null>(null);
  const [sort, setSort] = useState<Sort>("group");

  const groups = useApi<WatchlistGroup[]>(signedIn ? "/api/watchlists" : null, { auth: true });
  const rules = useApi<AlertRule[]>(signedIn ? "/api/alerts/rules" : null, { auth: true });
  const list = groups.data ?? [];
  const group = list.find((g) => g.id === groupId) ?? list[0];
  const ids = (group?.items ?? []).map((i) => i.stockId).slice(0, 50);
  const idParam = ids.join(",");
  const quotes = useApi<{ items: ScreenerQuote[] }>(ids.length ? `/api/screener/quotes?ids=${idParam}` : null, { refreshMs: 15_000 });
  const intraday = useApi<IntradaySeries[]>(ids.length ? `/api/market/intraday?ids=${idParam}` : null, { refreshMs: 60_000 });

  const quoteById = useMemo(() => new Map((quotes.data?.items ?? []).map((q) => [q.stockId, q])), [quotes.data]);
  const closesById = useMemo(() => new Map((intraday.data ?? []).map((s) => [s.stockId, s.closes.map(Number)])), [intraday.data]);

  const rows = useMemo(() => {
    const items = [...(group?.items ?? [])];
    if (sort === "change") items.sort((a, b) => (quoteById.get(b.stockId)?.changeRate ?? -Infinity) - (quoteById.get(a.stockId)?.changeRate ?? -Infinity));
    if (sort === "name") items.sort((a, b) => a.name.localeCompare(b.name, "ko"));
    return items;
  }, [group, sort, quoteById]);

  if (auth === "signedOut") return <SignInRequired message="관심종목을 보려면 로그인해 주세요" />;
  if (auth === "unknown" || groups.loading) return <Loading />;

  const qs = ids.map((id) => quoteById.get(id)).filter((q): q is ScreenerQuote => !!q);
  const up = qs.filter((q) => q.changeRate > 0).length;
  const down = qs.filter((q) => q.changeRate < 0).length;
  const eventsToday = qs.reduce((n, q) => n + (q.todayEvents?.length ?? 0), 0);
  const alertStocks = new Set((rules.data ?? []).filter((r) => r.isActive && r.stockId != null && ids.includes(r.stockId)).map((r) => r.stockId)).size;

  return (
    <Screen
      title="관심종목"
      onRefresh={() => { groups.reload(); quotes.reload(); intraday.reload(); rules.reload(); }}
      actions={
        <Glass style={s.toolbar}>
          <View style={s.toolbarRow}>
            <Pressable onPress={() => setSort(NEXT_SORT[sort])} accessibilityRole="button" accessibilityLabel={`정렬: ${SORT_LABEL[sort]}`} style={s.toolbarButton}>
              <Icon name="sort" size={20} color={c.text} />
            </Pressable>
            <Pressable onPress={() => router.push("/search")} accessibilityRole="button" accessibilityLabel="종목 추가" style={s.toolbarButton}>
              <Icon name="plus" size={22} color={c.text} strokeWidth={2.2} />
            </Pressable>
          </View>
        </Glass>
      }
    >
      {list.length > 1 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={s.chips} accessibilityRole="tablist" accessibilityLabel="관심종목 그룹">
          {list.map((g) => <Chip key={g.id} label={`${g.name} ${g.items.length}`} selected={g.id === group?.id} onPress={() => setGroupId(g.id)} />)}
        </ScrollView>
      )}

      {rows.length === 0 ? (
        <EmptyState fill={false} title="관심종목이 없어요" sub="종목을 검색해서 더하면 움직일 때 이유와 함께 알려 드려요." action={{ label: "종목 찾기", onPress: () => router.push("/search") }} />
      ) : (
        <>
          <View style={s.tiles}>
            <Tile label="상승 / 하락">
              <Text style={[font.title, tabular, { color: c.text }]}>
                <Text style={{ color: market.up }}>{up}</Text> / <Text style={{ color: market.down }}>{down}</Text>
              </Text>
            </Tile>
            <Tile label="오늘 이벤트"><Text style={[font.title, tabular, { color: c.text }]}>{eventsToday}건</Text></Tile>
            <Tile label="알림 켜짐"><Text style={[font.title, tabular, { color: c.text }]}>{alertStocks}종목</Text></Tile>
          </View>
          {sort !== "group" && <Text style={[font.footnote, s.sortNote, { color: c.muted }]}>{SORT_LABEL[sort]}</Text>}

          <GroupedList>
            {rows.map((item) => {
              const q = quoteById.get(item.stockId);
              const ev = q?.todayEvents?.find((t) => BADGE_EVENTS.has(t));
              const usd = q?.market === "NASDAQ" || q?.market === "NYSE" || q?.market === "US";
              return (
                <Pressable
                  key={item.id}
                  onPress={() => router.push(`/stocks/${item.symbol}`)}
                  accessibilityRole="button"
                  style={({ pressed }) => [s.row, pressed && { backgroundColor: c.raised }]}
                >
                  <View style={s.rowMain}>
                    <View style={s.nameRow}>
                      <Text style={[font.headline, { color: c.text }]} numberOfLines={1}>{item.name}</Text>
                      {ev && <Badge label={EVENT_LABEL[ev] ?? ev} tone={eventTone(ev)} />}
                    </View>
                    <Text style={[font.footnote, { color: c.muted }]}>
                      {item.symbol}{q?.volumeMultiple != null ? ` · 거래량 ${q.volumeMultiple.toFixed(1)}×` : ""}
                    </Text>
                  </View>
                  <Sparkline values={closesById.get(item.stockId) ?? []} />
                  <View style={s.price}>
                    <Text style={[font.headline, tabular, { color: c.text }]}>{q ? formatPrice(q.price, usd ? "USD" : "KRW") : "—"}</Text>
                    {q && <ChangePill value={q.changeRate} />}
                  </View>
                </Pressable>
              );
            })}
          </GroupedList>
        </>
      )}
    </Screen>
  );
}

function Tile({ label, children }: { label: string; children: React.ReactNode }) {
  const { c } = useTheme();
  return (
    <View style={[s.tile, { backgroundColor: c.card }]}>
      <Text style={[font.caption, { color: c.muted }]}>{label}</Text>
      {children}
    </View>
  );
}

const s = StyleSheet.create({
  toolbar: { height: 44, borderRadius: 22 },
  toolbarRow: { flexDirection: "row" },
  toolbarButton: { width: 48, height: 44, alignItems: "center", justifyContent: "center" },
  chips: { gap: 8 },
  tiles: { flexDirection: "row", gap: 8 },
  tile: { flex: 1, paddingVertical: 12, paddingHorizontal: 14, borderRadius: radius.tile, gap: 2 },
  sortNote: { paddingHorizontal: 16, marginBottom: -6 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 13, paddingHorizontal: 16 },
  rowMain: { flex: 1, minWidth: 0, gap: 2 },
  nameRow: { flexDirection: "row", alignItems: "center", gap: 6 },
  price: { width: 84, alignItems: "flex-end", gap: 4 },
});
