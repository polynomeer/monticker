import { StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import { Badge, Card, Icon, Sparkline, withAlpha, type IconName } from "@/components/ui";
import { EVENT_LABEL, eventTone } from "@/lib/events";
import { feedTime, PRICE_EVENTS, type FeedItem } from "@/lib/feed";
import { formatPct } from "@/lib/format";

/** 피드 카드 한 장. 가격 이벤트는 스파크라인이 있는 큰 카드, 나머지는 아이콘 + 두 줄 */
export function FeedCard({ item, closes, now, unread }: { item: FeedItem; closes?: number[]; now: Date; unread?: boolean }) {
  const { c, market } = useTheme();
  const router = useRouter();
  const { width } = useWindowDimensions();

  if (item.kind === "event" && PRICE_EVENTS.has(item.event.eventType)) {
    const { event, stock } = item;
    const tone = eventTone(event.eventType);
    const label = EVENT_LABEL[event.eventType] ?? "이벤트";
    const badge = stock.changeRate != null && event.eventType !== "VOLUME_SURGE" ? `${label} ${formatPct(stock.changeRate)}` : label;
    return (
      <Card onPress={() => router.push(`/stocks/${stock.symbol}`)} style={s.big} accessibilityLabel={`${stock.name} ${badge}. ${event.description ?? event.title}`}>
        <View style={s.head}>
          <View style={[s.avatar, { backgroundColor: c.raised }]}><Text style={[font.subhead, s.semibold, { color: c.text }]}>{stock.name.slice(0, 1)}</Text></View>
          <View style={s.flex}>
            <Text style={[font.callout, { color: c.text }]}>{stock.name}</Text>
            <Text style={[font.caption, { color: c.muted }]}>{item.relation === "held" ? "보유" : "관심종목"} · {feedTime(item.time, now)}</Text>
          </View>
          <Badge label={badge} tone={tone} />
          {unread && <View accessibilityLabel="읽지 않음" style={[s.unread, { backgroundColor: c.accent }]} />}
        </View>
        {closes && closes.length > 1 && (
          <Sparkline values={closes} width={width - 64} height={52} strokeWidth={2.2} color={tone === "down" ? market.down : tone === "up" ? market.up : undefined} marker={{ index: closes.length - 1, color: c.accent }} />
        )}
        <Text style={[font.subhead, s.body, { color: c.body }]}>{event.description ?? event.title}</Text>
      </Card>
    );
  }

  let icon: IconName = "file";
  let color = c.muted;
  let title = "";
  let sub = "";
  let onPress = () => {};
  if (item.kind === "event") {
    const disclosure = item.event.eventType === "DISCLOSURE_PUBLISHED";
    icon = "file";
    color = disclosure ? c.disclosure : c.muted;
    title = `${item.stock.name} ${disclosure ? "새 공시" : (EVENT_LABEL[item.event.eventType] ?? "소식")}`;
    sub = item.event.title;
    onPress = () => router.push(`/stocks/${item.stock.symbol}`);
  } else if (item.kind === "signal") {
    icon = "flask";
    color = c.accent;
    title = `${item.signal.ruleSetName} ${item.signal.direction === "BUY" ? "매수" : "매도"} 신호`;
    sub = `${item.signal.source === "SUBSCRIBED" ? "구독 전략" : "내 전략"} · ${item.signal.stockName} · 모의 신호, 실주문 없음`;
    onPress = () => router.push(`/stocks/${item.signal.symbol}`);
  } else {
    icon = "alert";
    color = c.danger;
    title = `리스크 한도 ${item.usedPct >= 100 ? "도달" : "80% 근접"}`;
    sub = `일간 손실 한도의 ${item.usedPct}%를 썼습니다.`;
    onPress = () => router.navigate("/account");
  }

  return (
    <Card onPress={onPress} style={s.row} accessibilityLabel={`${title}. ${sub}`}>
      <View style={[s.icon, { backgroundColor: withAlpha(color, 0.16) }]}><Icon name={icon} size={17} color={color} strokeWidth={2.2} /></View>
      <View style={s.flex}>
        <View style={s.titleRow}>
          <Text style={[font.callout, s.flex, { color: c.text }]} numberOfLines={1}>{title}</Text>
          {item.kind !== "risk" && <Text style={[font.caption, { color: c.muted }]}>{feedTime(item.time, now)}</Text>}
        </View>
        <Text style={[font.subhead, s.sub, { color: c.soft }]} numberOfLines={2}>{sub}</Text>
      </View>
    </Card>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1 },
  big: { gap: 10 },
  head: { flexDirection: "row", alignItems: "center", gap: 10 },
  avatar: { width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center" },
  semibold: { fontWeight: "600" },
  unread: { width: 9, height: 9, borderRadius: 5 },
  body: { lineHeight: 21 },
  row: { flexDirection: "row", gap: 12, paddingVertical: 14 },
  icon: { width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center" },
  titleRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  sub: { fontSize: 14, lineHeight: 20 },
});
