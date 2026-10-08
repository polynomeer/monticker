"use client";

import { useState, useEffect, type ChangeEvent, type ReactNode } from "react";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Btn, Checkbox, Chip, Divider, Field, H2, Panel, PanelRow, PreviewTag, TerminalPage, Toggle } from "@/components/terminal";
import { SettingsNav } from "@/components/settings/SettingsNav";
import { MarketingConsentRow } from "@/components/settings/MarketingConsentRow";
import { crossesMidnight, quietHoursError, quietHoursStat } from "@/components/settings/quietHours";
import { useQueryClient } from "@tanstack/react-query";

/** ADR-082/093 — 서버 NotificationPreferenceRequest와 같은 필드·기본값(V78·V90 notification_preferences). */
interface NotifPref {
  allEnabled: boolean;
  pushEnabled: boolean;
  emailEnabled: boolean;
  priceAlertPush: boolean;
  priceAlertEmail: boolean;
  volumeSurgePush: boolean;
  volumeSurgeEmail: boolean;
  newsAlertPush: boolean;
  newsAlertEmail: boolean;
  quantSignalPush: boolean;
  quantSignalEmail: boolean;
  fillsPush: boolean;
  fillsEmail: boolean;
  strategyMarketNewsPush: boolean;
  strategyMarketNewsEmail: boolean;
  weeklyReportEmail: boolean;
  /** ADR-093 — 방해 금지 시간(KST "HH:mm", 자정을 넘을 수 있다) */
  quietHoursEnabled: boolean;
  quietHoursStart: string;
  quietHoursEnd: string;
}

const DEFAULT: NotifPref = {
  allEnabled: true,
  pushEnabled: true,
  emailEnabled: true,
  priceAlertPush: true,
  priceAlertEmail: false,
  volumeSurgePush: true,
  volumeSurgeEmail: false,
  newsAlertPush: true,
  newsAlertEmail: false,
  quantSignalPush: true,
  quantSignalEmail: false,
  fillsPush: true,
  fillsEmail: false,
  strategyMarketNewsPush: false,
  strategyMarketNewsEmail: false,
  weeklyReportEmail: true,
  quietHoursEnabled: false,
  quietHoursStart: "22:00",
  quietHoursEnd: "07:00",
};

function Row({ title, sub, preview, children, extra }: { title: string; sub: ReactNode; preview?: boolean; children: ReactNode; extra?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-tm-line py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="flex items-center gap-2 font-semibold">
          {title}
          {preview && <PreviewTag />}
        </span>
        <span className="text-xs text-tm-muted">{sub}</span>
        {extra}
      </div>
      {children}
    </div>
  );
}

/** 끌 수 없는 알림 — 놓치면 이중 주문 같은 실제 손해로 이어지는 것(서버가 항상 보낸다) */
function AlwaysOnRow({ title, sub }: { title: string; sub: string }) {
  return (
    <Row title={title} sub={sub}>
      <span className="inline-flex h-[22px] items-center rounded-md bg-[#22392c] px-2 text-xs font-semibold text-dracula-green">항상 받음</span>
    </Row>
  );
}

export default function NotificationSettingsPage() {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [pref, setPref] = useState<NotifPref>(DEFAULT);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    authFetch("/api/users/me/notification-preferences")
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => { if (data) setPref({ ...DEFAULT, ...data }); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const quietError = pref.quietHoursEnabled ? quietHoursError(pref.quietHoursStart, pref.quietHoursEnd) : null;

  const save = async () => {
    if (quietError) return;
    setSaving(true);
    try {
      const res = await authFetch("/api/users/me/notification-preferences", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(pref),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        throw new Error(body?.message ?? "다시 시도해주세요.");
      }
      // 알림 화면의 "전달 채널"이 새 설정으로 다시 계산되게
      void queryClient.invalidateQueries({ queryKey: ["notification", "channels"] });
      toast({ type: "success", title: "저장 완료", message: "알림 설정이 저장되었습니다." });
    } catch (e) {
      toast({ type: "error", title: "저장 실패", message: e instanceof Error && e.message ? e.message : "다시 시도해주세요." });
    } finally {
      setSaving(false);
    }
  };

  const set = (key: keyof NotifPref) => (v: boolean) => setPref((p) => ({ ...p, [key]: v }));
  const setTime = (key: "quietHoursStart" | "quietHoursEnd") => (e: ChangeEvent<HTMLInputElement>) =>
    setPref((p) => ({ ...p, [key]: e.target.value }));

  /** 종류별 켜기/끄기 — 서버는 푸시/이메일을 따로 저장하므로, 켜면 푸시부터 켜고 끄면 둘 다 끈다 */
  type BoolKey = { [K in keyof NotifPref]: NotifPref[K] extends boolean ? K : never }[keyof NotifPref];
  const pair = (push: BoolKey, email: BoolKey) => ({
    on: pref[push] || pref[email],
    toggle: (v: boolean) => setPref((p) => ({ ...p, [push]: v ? true : false, [email]: v ? p[email] : false })),
    channels: (
      <span className="mt-1 flex flex-wrap gap-1.5">
        <Chip active={pref[push]} onClick={() => set(push)(!pref[push])}>푸시</Chip>
        <Chip active={pref[email]} onClick={() => set(email)(!pref[email])}>이메일</Chip>
      </span>
    ),
  });

  const price = pair("priceAlertPush", "priceAlertEmail");
  const volume = pair("volumeSurgePush", "volumeSurgeEmail");
  const quant = pair("quantSignalPush", "quantSignalEmail");
  const fills = pair("fillsPush", "fillsEmail");
  const market = pair("strategyMarketNewsPush", "strategyMarketNewsEmail");
  const news = pair("newsAlertPush", "newsAlertEmail");
  // 주간 리포트는 설정만 저장되고 보내는 코드가 아직 없다(준비 중) — 켜진 알림 수에 넣지 않는다
  const kinds = [price.on, volume.on, news.on, quant.on, fills.on, market.on];
  const onCount = pref.allEnabled ? kinds.filter(Boolean).length : 0;
  const channels = [pref.pushEnabled && "푸시", pref.emailEnabled && "이메일"].filter(Boolean).join(" · ") || "없음";

  return (
    <TerminalPage
      title="알림 설정"
      crumb="설정"
      stats={[
        { label: "켜진 알림", value: loading ? "—" : `${onCount} / ${kinds.length}` },
        { label: "채널", value: loading ? "—" : channels },
        { label: "방해 금지", value: loading ? "—" : quietHoursStat(pref.quietHoursEnabled, pref.quietHoursStart, pref.quietHoursEnd) },
      ]}
    >
      <PanelRow>
        <SettingsNav />
        <Panel
          tabs={["알림 설정"]}
          actions={[]}
          closable={false}
          className="flex-[999_1_520px]"
          bodyClassName="p-5"
          right={
            <Btn size="sm" onClick={save} disabled={saving || loading || !!quietError}>
              {saving ? "저장 중..." : "저장"}
            </Btn>
          }
        >
          {loading ? (
            <p className="m-0 py-8 text-center text-tm-muted">로딩 중...</p>
          ) : (
            <>
              <H2>전체</H2>
              <div>
                <Row title="전체 알림" sub="끄면 아래 알림이 모두 중지됩니다. ‘결과 확인 중’ 주문·조건부 주문 실패 알림은 계속 받습니다">
                  <Toggle checked={pref.allEnabled} onChange={set("allEnabled")} label="전체 알림" />
                </Row>
              </div>
              {!pref.allEnabled && (
                <p className="m-0 mt-2 text-xs text-dracula-orange">전체 알림이 꺼져 있어 아래 설정과 무관하게 끌 수 있는 알림은 보내지 않습니다.</p>
              )}

              <H2>가격·이벤트</H2>
              <div>
                <Row title="가격 알림" sub="설정한 목표가 도달" extra={price.channels}>
                  <Toggle checked={price.on} onChange={price.toggle} label="가격 알림" />
                </Row>
                <Row title="거래량 급증" sub="거래량 급증 알림 규칙 · 관심종목 거래량 급증 이벤트" extra={volume.channels}>
                  <Toggle checked={volume.on} onChange={volume.toggle} label="거래량 급증" />
                </Row>
                <Row
                  title="뉴스·공시"
                  sub="관심종목의 새 뉴스(6시간 이내)와 중요 DART 공시 · 알림은 시간당 5건까지, 넘으면 알림 이력에만 남깁니다"
                  extra={news.channels}
                >
                  <Toggle checked={news.on} onChange={news.toggle} label="뉴스·공시" />
                </Row>
                <Row title="퀀트 시그널" sub="내 전략의 포워드 테스트 매수·매도 신호 (모의 신호 — 주문은 나가지 않습니다)" extra={quant.channels}>
                  <Toggle checked={quant.on} onChange={quant.toggle} label="퀀트 시그널" />
                </Row>
              </div>

              <H2>계좌</H2>
              <div>
                <Row title="체결·정산" sub="실전 주문 체결, T+2 정산 완료" extra={fills.channels}>
                  <Toggle checked={fills.on} onChange={fills.toggle} label="체결·정산" />
                </Row>
                <AlwaysOnRow title="리스크 경고" sub="한도의 80%에 이르면 하루 한 번(항목별) · 끌 수 없음" />
                <AlwaysOnRow title="‘결과 확인 중’ 주문" sub="증권사 응답이 없을 때, 그리고 결과가 확인됐을 때 알림 · 끌 수 없음" />
              </div>

              <H2>리포트</H2>
              <div>
                <Row title="주간 투자 행동 리포트" sub="매주 월요일 · 이메일 — 아직 보내지 않습니다" preview>
                  <Toggle checked={false} disabled label="주간 투자 행동 리포트 (준비 중)" />
                </Row>
                <Row
                  title="전략 마켓 소식"
                  sub="새 검증 전략 · 프로모션 (광고성 — 아래 마케팅 정보 수신 동의도 있어야 보냅니다)"
                  extra={market.channels}
                >
                  <Toggle checked={market.on} onChange={market.toggle} label="전략 마켓 소식" />
                </Row>
              </div>

              <H2>수신 동의</H2>
              <div>
                <MarketingConsentRow />
              </div>
            </>
          )}
        </Panel>

        <Panel tabs={["전달 채널"]} actions={[]} closable={false} className="flex-[1_1_300px] self-start">
          <div className="flex flex-col gap-2.5">
            <Checkbox checked={pref.pushEnabled} onChange={set("pushEnabled")} label="앱 푸시" disabled={loading} />
            <Checkbox checked={pref.emailEnabled} onChange={set("emailEnabled")} label="이메일" disabled={loading} />
            <Checkbox checked={false} disabled label="카카오 알림톡" sub="[연동 예정]" />
          </div>
          <Divider />
          <div className="flex items-center justify-between">
            <span className="font-semibold">방해 금지 시간</span>
            <Toggle checked={pref.quietHoursEnabled} onChange={set("quietHoursEnabled")} label="방해 금지 시간" disabled={loading} />
          </div>
          <div className="flex gap-2">
            <Field label="시작 (KST)" type="time" value={pref.quietHoursStart} onChange={setTime("quietHoursStart")} disabled={loading || !pref.quietHoursEnabled} />
            <Field label="종료 (KST)" type="time" value={pref.quietHoursEnd} onChange={setTime("quietHoursEnd")} disabled={loading || !pref.quietHoursEnabled} />
          </div>
          {quietError ? (
            <span role="alert" className="text-xs text-[#ff8a8a]">{quietError}</span>
          ) : pref.quietHoursEnabled && crossesMidnight(pref.quietHoursStart, pref.quietHoursEnd) ? (
            <span className="text-xs text-tm-muted">다음 날 {pref.quietHoursEnd}까지 이어집니다.</span>
          ) : null}
          <span className="text-xs text-tm-muted">
            방해 금지 시간에는 앱 푸시를 보내지 않습니다(나중에 몰아서 보내지 않음). 고른 이메일과 알림 이력은 그대로 남습니다.
            리스크 경고·‘결과 확인 중’ 주문·조건부 주문 실패 알림은 방해 금지 시간에도 바로 전달됩니다.
          </span>
          <span className="text-xs text-tm-muted">채널 변경도 ‘저장’을 눌러야 반영됩니다.</span>
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
