"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Btn, BtnLink, H2, Icon, Notice, Panel, PanelRow, TerminalPage, Toggle, type IconName } from "@/components/terminal";
import { SettingsNav } from "@/components/settings/SettingsNav";
import { useIsLoggedIn } from "@/components/home/data";
import { useInterestOrdering, useSavePreferences, useUserPreferences } from "@/hooks/useUserPreferences";
import { INTEREST_SECTORS, INTEREST_SECTOR_KEYWORDS, interestLabel, type InterestSector } from "@/lib/interestSectors";
import type { UsageStyle } from "@/services/onboarding";
import { cn } from "@/lib/utils";

/** 온보딩과 같은 선택지 — 서버 enum(V86 CHECK)과 같은 키 */
const STYLES: { key: UsageStyle; icon: IconName; title: string; desc: string }[] = [
  { key: "OBSERVE", icon: "eye", title: "관찰 위주", desc: "차트와 이벤트를 먼저 익히고 싶어요" },
  { key: "EVENT_TRADING", icon: "trend", title: "단기 이벤트 매매", desc: "급등·급락 신호에 빠르게 반응하고 싶어요" },
  { key: "QUANT", icon: "flask", title: "규칙 기반 퀀트", desc: "전략을 만들고 검증하고 싶어요" },
];

function choiceClass(on: boolean) {
  return on
    ? "border-[1.5px] border-dracula-purple bg-[#3a2f52] text-dracula-fg font-semibold"
    : "border border-tm-line2 bg-tm-inner text-tm-soft hover:text-dracula-fg";
}

function sameSet(a: readonly string[], b: readonly string[]) {
  return a.length === b.length && a.every((x) => b.includes(x));
}

function SettingRow({ title, sub, children }: { title: ReactNode; sub: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-tm-line py-3">
      <div className="flex flex-col gap-0.5">
        <span className="font-semibold">{title}</span>
        <span className="text-xs text-tm-muted">{sub}</span>
      </div>
      {children}
    </div>
  );
}

/**
 * 설정 › 관심 분야 (ADR-099). 온보딩에서 고른 관심 분야·사용 방식을 바꾸고(PUT preferences),
 * 홈·알림의 "관심 분야 순" 스위치를 켜고 끈다(PATCH preferences). 관심 분야는 정렬·강조 신호일 뿐 종목 추천이 아니다.
 */
export default function InterestSettingsPage() {
  const isLoggedIn = useIsLoggedIn();
  const { data: prefs, isLoading } = useUserPreferences(isLoggedIn);
  const ordering = useInterestOrdering(isLoggedIn);
  const save = useSavePreferences();

  const [sectors, setSectors] = useState<InterestSector[]>([]);
  const [style, setStyle] = useState<UsageStyle | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);

  // 서버 값으로 채운다 — 사용자가 고치는 중이면 덮어쓰지 않는다
  useEffect(() => {
    if (!prefs || dirty) return;
    setSectors(prefs.interestSectors);
    setStyle(prefs.usageStyle);
  }, [prefs, dirty]);

  const changed = !!prefs && (!sameSet(sectors, prefs.interestSectors) || style !== prefs.usageStyle);
  const orderingOn = prefs?.interestOrdering !== false;

  const submit = () => {
    setSaved(false);
    save.mutate(
      { sectors, style },
      { onSuccess: () => { setDirty(false); setSaved(true); } },
    );
  };

  const stats = [
    { label: "관심 분야", value: prefs ? (prefs.interestSectors.length ? `${prefs.interestSectors.length}개` : "없음") : "—" },
    { label: "관심 분야 순", value: !prefs ? "—" : !prefs.interestSectors.length ? "해당 없음" : orderingOn ? "켜짐" : "꺼짐" },
  ];

  return (
    <TerminalPage title="관심 분야" crumb="설정" stats={isLoggedIn ? stats : []}>
      <PanelRow>
        <SettingsNav />
        <Panel tabs={["관심 분야·사용 방식"]} actions={[]} closable={false} className="flex-[999_1_600px]" bodyClassName="p-5">
          {!isLoggedIn ? (
            <div className="flex flex-col items-center gap-4 py-16 text-center">
              <p className="text-13 text-tm-muted">관심 분야를 바꾸려면 로그인이 필요합니다.</p>
              <BtnLink href="/login">로그인</BtnLink>
            </div>
          ) : isLoading ? (
            <div className="h-40 animate-pulse rounded-lg bg-tm-inner" />
          ) : (
            <>
              <H2 sub="여러 개 고를 수 있어요">관심 분야</H2>
              <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(140px,1fr))" }}>
                {INTEREST_SECTORS.map((s) => {
                  const on = sectors.includes(s.key);
                  return (
                    <button
                      key={s.key}
                      type="button"
                      aria-pressed={on}
                      onClick={() => {
                        setDirty(true);
                        setSaved(false);
                        setSectors((v) => (on ? v.filter((x) => x !== s.key) : [...v, s.key]));
                      }}
                      className={cn("h-11 rounded-[10px] text-sm", choiceClass(on))}
                    >
                      {s.label}
                    </button>
                  );
                })}
              </div>

              <H2>사용 방식</H2>
              <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(200px,1fr))" }}>
                {STYLES.map((s) => {
                  const on = style === s.key;
                  return (
                    <button
                      key={s.key}
                      type="button"
                      aria-pressed={on}
                      onClick={() => {
                        setDirty(true);
                        setSaved(false);
                        setStyle(on ? null : s.key);
                      }}
                      className={cn("flex flex-col gap-1.5 rounded-xl p-4 text-left font-normal text-dracula-fg", choiceClass(on))}
                    >
                      <Icon name={s.icon} size={20} className="text-dracula-purple" />
                      <span className="font-bold">{s.title}</span>
                      <span className="text-xs leading-normal text-tm-muted">{s.desc}</span>
                    </button>
                  );
                })}
              </div>

              <div className="flex flex-wrap items-center gap-3">
                <Btn onClick={submit} disabled={!changed || save.isPending}>{save.isPending ? "저장 중..." : "저장"}</Btn>
                {saved && !changed && <span className="text-xs text-dracula-green">저장했습니다</span>}
              </div>
              {save.error && <Notice tone="danger">{(save.error as Error).message}</Notice>}

              <H2>홈·알림에 반영</H2>
              <div>
                <SettingRow
                  title="관심 분야 순"
                  sub={
                    prefs?.interestSectors.length
                      ? "홈 섹터 히트맵·이벤트 피드에서 관심 분야 항목을 앞에 두고 '관심'으로 표시합니다. 숨기는 정보는 없습니다"
                      : "관심 분야를 저장하면 쓸 수 있습니다"
                  }
                >
                  <Toggle
                    checked={orderingOn && !!prefs?.interestSectors.length}
                    label="관심 분야 순"
                    disabled={!prefs?.interestSectors.length || ordering.pending}
                    onChange={ordering.setEnabled}
                  />
                </SettingRow>
              </div>
              {ordering.error && <Notice tone="danger">{ordering.error.message}</Notice>}

              <Notice tone="info">
                관심 분야는 화면의 표시 순서와 알림 이력의 &lsquo;관심 분야&rsquo; 필터에만 쓰입니다. 푸시·이메일 발송량과 방해 금지 시간은 바뀌지 않으며,
                특정 종목을 추천하지 않습니다. 업종은 종목의 업종명으로 맞추며, 배당주·ETF는 업종으로 구분할 수 없어 정렬에 쓰이지 않습니다.
              </Notice>
              {prefs && prefs.interestSectors.length > 0 && (
                <ul className="m-0 flex list-none flex-col gap-1 p-0 text-xs text-tm-muted">
                  {prefs.interestSectors.map((k) => (
                    <li key={k}>
                      <span className="font-semibold text-tm-soft">{interestLabel(k)}</span>
                      {" — "}
                      {INTEREST_SECTOR_KEYWORDS[k].length ? `업종명에 ${INTEREST_SECTOR_KEYWORDS[k].join(", ")} 포함` : "업종으로 구분하지 않음"}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
