import { useEffect, useState } from "react";
import { Alert, Linking, StyleSheet, Text, View } from "react-native";
import { useRouter } from "expo-router";
import Constants from "expo-constants";
import type { BrokerageAccountResponse, RiskLimits, UserProfile } from "@monticker/types";
import { useTheme } from "@/theme/ThemeProvider";
import { font } from "@/theme/type";
import type { SchemePreference } from "@/theme/palette";
import { useAuthState } from "@/services/useAuth";
import { authFetch, getSignedInEmail, logout } from "@/services/auth";
import { unregisterPushToken } from "@/services/notifications";
import { getWebBase } from "@/services/api";
import { useApi } from "@/services/useApi";
import { brokerName, setTradingMode } from "@/services/mode";
import { canUseAppLock, setAppLock, useAppLockEnabled } from "@/services/appLock";
import { Card, GroupedList, Icon, ListRow, RowIcon, Screen, SectionLabel, Segmented, Switch } from "@/components/ui";
import { BackButton } from "@/components/ui/BackButton";
import type { IconName } from "@/components/ui";

const UPDOWN_LABEL = { auto: "모드에 맞춤", redUp: "빨강 상승", greenUp: "초록 상승" } as const;

/** ADR-082·093 알림 설정(PUT은 전체 본문) */
type NotificationPrefs = Record<string, boolean | string | null> & {
  pushEnabled: boolean; emailEnabled: boolean; weeklyReportEmail: boolean;
  quietHoursEnabled: boolean | null; quietHoursStart: string | null; quietHoursEnd: string | null;
};

/** 설정 아이콘 사각형 색 — 시안의 iOS 설정형 색(테마와 무관한 고정 색) */
const TILE = { wallet: "#b88a00", bank: "#2d6fd6", star: "#7c4dd6", bell: "#d6334a", moon: "#5146b8", mail: "#0b7a90", shield: "#c25a00", alert: "#d6334a" };

function Tile({ name }: { name: keyof typeof TILE }) {
  return <RowIcon color={TILE[name]}><Icon name={name as IconName} size={17} color="#ffffff" strokeWidth={2.2} /></RowIcon>;
}

/**
 * 설정(ADR-113). 앱에서 바로 바꾸는 것은 토글(주간 리포트·잠금)과 화면 설정뿐이다.
 * 결제·증권사 연동·리스크 한도 편집·동의 철회처럼 확인 절차가 긴 것은 웹의 해당 화면을 연다.
 */
export default function SettingsScreen() {
  const { c, market, schemePreference, setSchemePreference, upDownPreference } = useTheme();
  const router = useRouter();
  const auth = useAuthState();
  const signedIn = auth === "signedIn";
  const [storedEmail, setStoredEmail] = useState<string | null>(null);
  const lock = useAppLockEnabled();
  const [lockAvailable, setLockAvailable] = useState(false);

  const profile = useApi<UserProfile>(signedIn ? "/api/users/me" : null, { auth: true });
  const paper = useApi<{ initialCapital: number }>(signedIn ? "/api/paper/account" : null, { auth: true });
  const broker = useApi<BrokerageAccountResponse>(signedIn ? "/api/brokerage/account" : null, { auth: true });
  const sub = useApi<{ planName: string }>(signedIn ? "/api/subscription/me" : null, { auth: true });
  const prefs = useApi<NotificationPrefs>(signedIn ? "/api/users/me/notification-preferences" : null, { auth: true });
  const limits = useApi<RiskLimits>(signedIn ? "/api/risk/limits" : null, { auth: true });
  const [weekly, setWeekly] = useState<boolean | null>(null);

  // 프로필 API가 답하기 전(또는 못 할 때)에는 로그인할 때 저장한 이메일을 보인다
  useEffect(() => { if (signedIn) getSignedInEmail().then(setStoredEmail); else setStoredEmail(null); }, [signedIn]);
  const email = profile.data?.email ?? storedEmail;
  const nickname = profile.data?.nickname ?? email?.split("@")[0] ?? null;
  useEffect(() => { canUseAppLock().then(setLockAvailable); }, []);
  useEffect(() => { if (prefs.data) setWeekly(prefs.data.weeklyReportEmail); }, [prefs.data]);

  const web = (path: string) => () => Linking.openURL(`${getWebBase()}${path}`);

  const toggleWeekly = async (v: boolean) => {
    if (!prefs.data) return;
    setWeekly(v);
    const r = await authFetch("/api/users/me/notification-preferences", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...prefs.data, weeklyReportEmail: v }),
    }).catch(() => null);
    if (!r?.ok) { setWeekly(!v); Alert.alert("저장하지 못했어요", "잠시 뒤 다시 시도해 주세요."); return; }
    prefs.reload();
  };

  const toggleLock = async (v: boolean) => {
    if (!(await setAppLock(v)) && v) Alert.alert("잠금을 켜지 못했어요", "인증이 취소됐거나 이 기기에서 쓸 수 없어요.");
  };

  const p = prefs.data;
  const channels = p ? [p.pushEnabled ? "푸시" : null, p.emailEnabled ? "이메일" : null].filter(Boolean).join(" · ") || "꺼짐" : undefined;
  const quiet = p ? (p.quietHoursEnabled ? `${p.quietHoursStart ?? "22:00"}–${p.quietHoursEnd ?? "07:00"}` : "꺼짐") : undefined;
  const linked = broker.data && broker.data.isActive && broker.data.tokenValid;
  const capital = paper.data ? `초기 자본 ${(Number(paper.data.initialCapital) / 10_000).toLocaleString("ko-KR")}만` : signedIn && !paper.loading ? "시작 전" : undefined;
  const version = Constants.expoConfig?.version ?? "";

  return (
    <Screen title="설정" leading={<BackButton />} bottomSpace={48}>
      {signedIn ? (
        <Card style={s.profile} accessibilityLabel={`${nickname ?? "내 계정"}, ${sub.data?.planName ?? "무료"} 플랜`}>
          <View style={[s.avatar, { backgroundColor: c.accent }]}>
            <Text style={[s.avatarText, { color: c.onAccent }]}>{(nickname ?? email ?? "M").slice(0, 1).toUpperCase()}</Text>
          </View>
          <View style={s.flex}>
            <Text style={[font.title, { color: c.text, fontWeight: "600" }]} numberOfLines={1}>{nickname ?? "내 계정"}</Text>
            <Text style={[font.footnote, { color: c.muted }]} numberOfLines={1}>{[email, `${sub.data?.planName ?? "무료"} 플랜`].filter(Boolean).join(" · ")}</Text>
          </View>
        </Card>
      ) : (
        <Card onPress={() => router.push("/login")} style={s.profile} accessibilityLabel="로그인">
          <View style={[s.avatar, { backgroundColor: c.raised }]}><Icon name="user" size={24} color={c.text} /></View>
          <View style={s.flex}>
            <Text style={[font.title, { color: c.text, fontWeight: "600" }]}>로그인</Text>
            <Text style={[font.footnote, { color: c.muted }]}>관심종목·모의투자·알림을 쓰려면 로그인해 주세요</Text>
          </View>
          <Icon name="chevronRight" size={14} color={c.chevron} strokeWidth={2.6} />
        </Card>
      )}

      {signedIn && (
        <>
          <SectionLabel>계정</SectionLabel>
          <GroupedList inset={58}>
            <ListRow label="모의투자 계좌" value={capital} icon={<Tile name="wallet" />} onPress={async () => { await setTradingMode("paper"); router.navigate("/account"); }} />
            <ListRow label="증권사 연동" value={linked ? `${brokerName(broker.data?.provider)} 연동됨` : "연동 안 됨"} icon={<Tile name="bank" />} onPress={web("/brokerage")} accessibilityHint="웹에서 엽니다" />
            <ListRow label="구독" value={sub.data?.planName ?? "무료"} icon={<Tile name="star" />} onPress={web("/subscription")} accessibilityHint="웹에서 엽니다" />
          </GroupedList>

          <SectionLabel>알림·투자</SectionLabel>
          <GroupedList inset={58}>
            <ListRow label="알림 채널" value={channels} icon={<Tile name="bell" />} onPress={web("/settings/notifications")} accessibilityHint="웹에서 엽니다" />
            <ListRow label="방해 금지 시간" value={quiet} icon={<Tile name="moon" />} onPress={web("/settings/notifications")} accessibilityHint="웹에서 엽니다" />
            <ListRow
              label="주간 행동 리포트"
              icon={<Tile name="mail" />}
              chevron={false}
              trailing={<Switch value={!!weekly} disabled={weekly == null} onValueChange={toggleWeekly} accessibilityLabel="주간 행동 리포트 이메일" />}
            />
            <ListRow label="리스크 한도" value={limits.data ? `일간 손실 ${Number(limits.data.dailyLossLimitPct)}%` : undefined} icon={<Tile name="shield" />} onPress={web("/risk")} accessibilityHint="웹에서 엽니다" />
            <ListRow label="가격 알림" icon={<Tile name="alert" />} onPress={() => router.push("/alerts")} />
          </GroupedList>
        </>
      )}

      <SectionLabel>화면</SectionLabel>
      <GroupedList>
        <View style={s.modeRow}>
          <Text style={[font.body, { color: c.text }]}>화면 모드</Text>
          <View style={s.modeControl}>
            <Segmented<SchemePreference>
              compact
              accessibilityLabel="화면 모드"
              value={schemePreference}
              onChange={setSchemePreference}
              options={[{ value: "system", label: "자동" }, { value: "light", label: "라이트" }, { value: "dark", label: "다크" }]}
            />
          </View>
        </View>
        <ListRow
          label="상승·하락 색"
          value={UPDOWN_LABEL[upDownPreference]}
          onPress={() => router.push("/settings/updown")}
          trailing={
            <View style={s.dots}>
              <View style={[s.dot, { backgroundColor: market.up }]} />
              <View style={[s.dot, { backgroundColor: market.down }]} />
            </View>
          }
        />
      </GroupedList>

      {signedIn && (
        <>
          <SectionLabel>보안·개인정보</SectionLabel>
          <GroupedList>
            <ListRow
              label="Face ID로 잠금"
              chevron={false}
              trailing={<Switch value={lock.enabled} disabled={!lockAvailable} onValueChange={toggleLock} accessibilityLabel="Face ID로 잠금" />}
            />
            <ListRow label="동의 내역" onPress={web("/consent")} accessibilityHint="웹에서 엽니다" />
          </GroupedList>

          <GroupedList>
            {/* 이 기기의 푸시 토큰을 먼저 해제하고(세션이 살아 있을 때) 세션을 끝낸다 */}
            <ListRow label="로그아웃" destructive chevron={false} onPress={async () => { await logout(unregisterPushToken); router.replace("/"); }} />
          </GroupedList>
        </>
      )}

      <Text style={[font.footnote, s.footer, { color: c.muted }]}>
        monticker {version} ·{" "}
        <Text onPress={web("/terms")} accessibilityRole="link">이용약관</Text> ·{" "}
        <Text onPress={web("/privacy")} accessibilityRole="link">개인정보처리방침</Text>
      </Text>
    </Screen>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1, minWidth: 0, gap: 2 },
  profile: { flexDirection: "row", alignItems: "center", gap: 14, paddingVertical: 14 },
  avatar: { width: 56, height: 56, borderRadius: 28, alignItems: "center", justifyContent: "center" },
  avatarText: { fontSize: 22, fontWeight: "700" },
  modeRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12, paddingHorizontal: 16, minHeight: 56 },
  modeControl: { width: 190 },
  dots: { flexDirection: "row", gap: 4 },
  dot: { width: 12, height: 12, borderRadius: 6 },
  footer: { textAlign: "center" },
});
