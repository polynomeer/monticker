"use client";

import { useState, useEffect, type ReactNode } from "react";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Btn, Checkbox, Chip, Divider, Field, H2, Panel, PanelRow, PreviewTag, TerminalPage, Toggle } from "@/components/terminal";
import { SettingsNav } from "@/components/settings/SettingsNav";
import { MarketingConsentRow } from "@/components/settings/MarketingConsentRow";

interface NotifPref {
  pushEnabled: boolean;
  emailEnabled: boolean;
  priceAlertPush: boolean;
  priceAlertEmail: boolean;
  newsAlertPush: boolean;
  newsAlertEmail: boolean;
  weeklyReportEmail: boolean;
}

const DEFAULT: NotifPref = {
  pushEnabled: true,
  emailEnabled: true,
  priceAlertPush: true,
  priceAlertEmail: false,
  newsAlertPush: true,
  newsAlertEmail: false,
  weeklyReportEmail: true,
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

/** 시안 요소 — 서버 설정 항목이 아직 없는 알림 종류 */
function PreviewRow({ title, sub, on = true }: { title: string; sub: string; on?: boolean }) {
  return (
    <Row title={title} sub={sub} preview>
      <Toggle checked={on} label={title} disabled />
    </Row>
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

  const save = async () => {
    setSaving(true);
    try {
      const res = await authFetch("/api/users/me/notification-preferences", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(pref),
      });
      if (!res.ok) throw new Error();
      toast({ type: "success", title: "저장 완료", message: "알림 설정이 저장되었습니다." });
    } catch {
      toast({ type: "error", title: "저장 실패", message: "다시 시도해주세요." });
    } finally {
      setSaving(false);
    }
  };

  const set = (key: keyof NotifPref) => (v: boolean) => setPref((p) => ({ ...p, [key]: v }));

  /** 종류별 켜기/끄기 — 서버는 푸시/이메일을 따로 저장하므로, 켜면 푸시부터 켜고 끄면 둘 다 끈다 */
  const pair = (push: keyof NotifPref, email: keyof NotifPref) => ({
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
  const news = pair("newsAlertPush", "newsAlertEmail");
  const onCount = [price.on, news.on, pref.weeklyReportEmail].filter(Boolean).length;
  const channels = [pref.pushEnabled && "푸시", pref.emailEnabled && "이메일"].filter(Boolean).join(" · ") || "없음";

  return (
    <TerminalPage
      title="알림 설정"
      crumb="설정"
      stats={[
        { label: "켜진 알림", value: loading ? "—" : `${onCount} / 3` },
        { label: "채널", value: loading ? "—" : channels },
        { label: "방해 금지", value: "—" },
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
            <Btn size="sm" onClick={save} disabled={saving || loading}>
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
                <PreviewRow title="전체 알림" sub="끄면 모든 알림이 중지됩니다" />
              </div>

              <H2>가격·이벤트</H2>
              <div>
                <Row title="가격 알림" sub="설정한 목표가 도달" extra={price.channels}>
                  <Toggle checked={price.on} onChange={price.toggle} label="가격 알림" />
                </Row>
                <PreviewRow title="거래량 급증" sub="관심종목 5분 평균 대비 3× 이상" />
                <Row title="뉴스·공시" sub="관심종목 관련 뉴스와 DART 공시" extra={news.channels}>
                  <Toggle checked={news.on} onChange={news.toggle} label="뉴스·공시" />
                </Row>
                <PreviewRow title="퀀트 시그널" sub="내 전략 · 구독 전략 신호" />
              </div>

              <H2>계좌</H2>
              <div>
                <PreviewRow title="체결·정산" sub="모의/실전 체결, T+2 정산 완료" />
                <PreviewRow title="리스크 경고" sub="한도 80% 도달 · 주문 차단" />
                <AlwaysOnRow title="‘결과 확인 중’ 주문" sub="증권사 응답이 없을 때, 그리고 결과가 확인됐을 때 알림 · 끌 수 없음" />
              </div>

              <H2>리포트</H2>
              <div>
                <Row title="주간 투자 행동 리포트" sub="매주 월요일 · 이메일">
                  <Toggle checked={pref.weeklyReportEmail} onChange={set("weeklyReportEmail")} label="주간 투자 행동 리포트" />
                </Row>
                <PreviewRow title="전략 마켓 소식" sub="새 검증 전략 · 프로모션" on={false} />
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
            <span className="flex items-center gap-2 font-semibold">방해 금지 시간 <PreviewTag /></span>
            <Toggle checked={false} label="방해 금지 시간" disabled />
          </div>
          <div className="flex gap-2">
            <Field label="시작" placeholder="22:00" disabled />
            <Field label="종료" placeholder="07:30" disabled />
          </div>
          <span className="text-xs text-tm-muted">리스크 경고와 ‘결과 확인 중’ 주문 알림은 방해 금지 시간에도 전달됩니다.</span>
          <span className="text-xs text-tm-muted">채널 변경도 ‘저장’을 눌러야 반영됩니다.</span>
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
