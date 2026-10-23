import { useRef, useState } from "react";
import { ScrollView, StyleSheet, Text, View, useWindowDimensions } from "react-native";
import { useRouter } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { useTheme } from "@/theme/ThemeProvider";
import { font, radius, tabular } from "@/theme/type";
import { authFetch, getAuthState } from "@/services/auth";
import { ONBOARDED_KEY } from "@/services/onboarding";
import { Badge, Button, Card, Icon, Sparkline, withAlpha } from "@/components/ui";


const PAGES = [
  {
    title: "주가가 움직이면,\n왜 움직였는지 먼저",
    body: "급등·급락과 거래량 급증을 공시·뉴스와 함께 묶어 알려 드립니다. 가상의 1,000만 원으로 바로 모의투자도 해 볼 수 있어요.",
    note: "투자 판단의 근거를 제공할 뿐, 매매를 권유하지 않습니다.",
  },
  {
    title: "실제 돈 없이,\n진짜처럼 연습하세요",
    body: "가상의 1,000만 원으로 사고팔아 보세요. 주문 전에 손실 한도와 집중도를 먼저 확인하고, 그때의 기분도 함께 남겨 습관을 돌아볼 수 있어요.",
    note: "모의투자 손익은 실제 계좌와 무관합니다.",
  },
  {
    title: "코딩 없이\n나만의 전략을 검증",
    body: "조건 블록을 조합해 전략을 만들고, 과거 데이터로 백테스트한 뒤 매일 포워드 테스트로 확인합니다. 신호가 나면 알려 드려요.",
    note: "전략 신호는 모의이며 실제 주문은 나가지 않습니다. 과거 성과가 미래 수익을 보장하지 않습니다.",
  },
];

/** 처음 실행 때 한 번 — 이벤트·모의투자·퀀트랩 소개 3장. 건너뛸 수 있고, 끝나면 기기에 기록한다 */
export default function OnboardingScreen() {
  const { c } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const [page, setPage] = useState(0);
  const pager = useRef<ScrollView>(null);

  const finish = async () => {
    await AsyncStorage.setItem(ONBOARDED_KEY, "1").catch(() => undefined);
    if (getAuthState() === "signedIn") {
      // 모의 계좌가 없으면 기본 시작 자금(1,000만)으로 만든다 — 이미 있으면 서버가 그대로 둔다(ADR-089)
      await authFetch("/api/paper/account", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ initialCapital: 10_000_000 }) }).catch(() => undefined);
      router.replace("/");
    } else {
      router.replace("/");
      router.push("/login");
    }
  };
  const next = () => {
    if (page === PAGES.length - 1) { finish(); return; }
    pager.current?.scrollTo({ x: (page + 1) * width, animated: true });
    setPage(page + 1);
  };

  return (
    <View style={[s.fill, { backgroundColor: c.bg, paddingTop: insets.top + 8, paddingBottom: insets.bottom + 16 }]}>
      <View style={s.top}>
        <Text style={[font.headline, { color: c.text }]}>{page === 0 ? "monticker" : ""}</Text>
        <Text onPress={finish} accessibilityRole="button" style={[font.subhead, { color: c.muted }]}>건너뛰기</Text>
      </View>
      <ScrollView
        ref={pager}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        onMomentumScrollEnd={(e) => setPage(Math.round(e.nativeEvent.contentOffset.x / width))}
        style={s.fill}
      >
        {PAGES.map((p, i) => (
          <View key={i} style={[s.page, { width }]}>
            <View style={s.art}>{i === 0 ? <EventArt /> : i === 1 ? <PaperArt /> : <QuantArt />}</View>
            <Text style={[s.title, { color: c.text }]} accessibilityRole="header">{p.title}</Text>
            <Text style={[font.subhead, s.body, { color: c.soft }]}>{p.body}</Text>
          </View>
        ))}
      </ScrollView>
      <View style={s.bottom}>
        <View style={s.dots} accessibilityLabel={`${PAGES.length}장 중 ${page + 1}장`}>
          {PAGES.map((_, i) => <View key={i} style={[s.dot, { backgroundColor: i === page ? c.accent : c.line, width: i === page ? 20 : 7 }]} />)}
        </View>
        <Button label={page === PAGES.length - 1 ? "시작하기" : "다음"} onPress={next} />
        <Text style={[font.caption, s.note, { color: c.muted }]}>{PAGES[page].note}</Text>
      </View>
    </View>
  );
}

/** 1장 — 관심종목이 움직였다는 예시 카드(실제 데이터 아님) */
function EventArt() {
  const { c, market } = useTheme();
  return (
    <Card style={s.artCard}>
      <View style={s.row}>
        <View style={[s.avatar, { backgroundColor: c.raised }]}><Text style={[font.subhead, { color: c.text, fontWeight: "600" }]}>삼</Text></View>
        <Text style={[font.headline, s.flex, { color: c.text }]}>삼성전자</Text>
        <Badge label="급등" tone="up" />
      </View>
      <Sparkline values={[40, 38, 42, 34, 36, 30, 32, 14, 8].map((v) => 52 - v)} width={260} height={48} color={market.up} marker={{ index: 7, color: c.accent }} />
      <Text style={[font.subhead, { color: c.body }]}>20분 사이 +3.8%, 평소 거래량의 3배 넘게 체결됐습니다.</Text>
      <View style={[s.toast, { backgroundColor: withAlpha(c.accent, 0.14) }]}>
        <Icon name="bell" size={14} color={c.accent} />
        <Text style={[font.footnote, { color: c.accent }]}>관심종목이 움직였어요</Text>
      </View>
    </Card>
  );
}

/** 2장 — 모의 총자산과 주문 전 리스크 체크 예시 */
function PaperArt() {
  const { c } = useTheme();
  return (
    <Card style={s.artCard}>
      <Text style={[font.footnote, { color: c.muted }]}>모의 총자산</Text>
      <Text style={[s.amount, tabular, { color: c.text }]}>10,000,000<Text style={[font.title, { color: c.soft }]}>원</Text></Text>
      <Text style={[font.caption, { color: c.muted }]}>가입하면 바로 지급 · 언제든 초기화</Text>
      <View style={[s.risk, { backgroundColor: c.fill }]}>
        <View style={s.row}>
          <Text style={[font.footnote, s.flex, { color: c.text, fontWeight: "600" }]}>주문 전 리스크 체크</Text>
          <Text style={[font.caption, { color: c.positive, fontWeight: "700" }]}>통과</Text>
        </View>
        <RiskLine label="일간 손실 한도" value="손실 0 / 298,786원" />
        <RiskLine label="종목 집중도" value="7.8% / 30%" />
      </View>
      <View style={s.row}>
        <Badge label="계획대로" tone="accent" />
        <Badge label="FOMO" tone="muted" />
      </View>
    </Card>
  );
}

function RiskLine({ label, value }: { label: string; value: string }) {
  const { c } = useTheme();
  return (
    <View style={s.row}>
      <Icon name="check" size={13} color={c.positive} strokeWidth={2.4} />
      <Text style={[font.footnote, s.flex, { color: c.body }]}>{label}</Text>
      <Text style={[font.footnote, tabular, { color: c.text }]}>{value}</Text>
    </View>
  );
}

/** 3장 — 조건 블록과 검증 단계 예시 */
function QuantArt() {
  const { c } = useTheme();
  const block = (t: string) => (
    <View style={[s.block, { backgroundColor: c.fill, borderColor: c.line }]}><Text style={[font.footnote, { color: c.text }]}>{t}</Text></View>
  );
  return (
    <Card style={s.artCard}>
      {block("종가 > 20일 이동평균")}
      <Text style={[font.caption, s.and, { color: c.accent }]}>그리고</Text>
      {block("RSI 30~70")}
      {block("거래량 2배 이상")}
      <View style={[s.risk, { backgroundColor: c.fill }]}>
        <View style={s.row}>
          <Text style={[font.footnote, s.flex, { color: c.text, fontWeight: "600" }]}>백테스트 → 포워드 테스트</Text>
          <Badge label="검증 중" tone="caution" />
        </View>
        <Text style={[font.caption, { color: c.muted }]}>과거 데이터 · 매일 장 마감 후 평가</Text>
      </View>
    </Card>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
  flex: { flex: 1 },
  top: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", paddingHorizontal: 20, height: 44 },
  page: { paddingHorizontal: 24, justifyContent: "flex-end", gap: 12, paddingBottom: 8 },
  art: { flex: 1, justifyContent: "center" },
  artCard: { gap: 10 },
  title: { fontSize: 30, fontWeight: "700", lineHeight: 38 },
  body: { lineHeight: 22 },
  bottom: { paddingHorizontal: 24, gap: 12, paddingTop: 12 },
  dots: { flexDirection: "row", gap: 6, alignSelf: "center" },
  dot: { height: 7, borderRadius: 4 },
  note: { textAlign: "center" },
  row: { flexDirection: "row", alignItems: "center", gap: 8 },
  avatar: { width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center" },
  toast: { flexDirection: "row", alignItems: "center", gap: 6, alignSelf: "flex-start", paddingHorizontal: 10, paddingVertical: 5, borderRadius: 12 },
  amount: { fontSize: 32, fontWeight: "700" },
  risk: { borderRadius: radius.tile, padding: 12, gap: 6 },
  block: { borderWidth: 1, borderRadius: 14, paddingVertical: 10, paddingHorizontal: 14 },
  and: { alignSelf: "center", fontWeight: "700" },
});
