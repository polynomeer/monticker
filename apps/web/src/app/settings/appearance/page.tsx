"use client";

import type { ReactNode } from "react";
import { H2, Panel, PanelRow, PreviewTag, TerminalPage, Toggle } from "@/components/terminal";
import { SettingsNav } from "@/components/settings/SettingsNav";
import { useThemeStore, CHART_THEMES, type ChartThemeKey } from "@/stores/themeStore";
import { useA11yStore, useReducedMotion, TEXT_SIZES, type TextSize } from "@/stores/a11yStore";
import { cn } from "@/lib/utils";

/** 시세 색상 — 기존 차트 테마(themeStore)에 시안의 이름을 붙인다 */
const MARKET_COLORS: { key: ChartThemeKey; name: string; sub: string }[] = [
  { key: "default", name: "글로벌", sub: "상승 초록 · 하락 빨강" },
  { key: "korean", name: "한국식", sub: "상승 빨강 · 하락 파랑" },
  { key: "classic", name: "색약 친화", sub: "상승 파랑 · 하락 주황" },
  { key: "mono", name: "모노", sub: "상승 밝은 회색 · 하락 어두운 회색" },
];

function choiceBorder(on: boolean) {
  return on ? "border-2 border-dracula-purple" : "border border-tm-line2 hover:border-tm-muted";
}

function ThemeCard({
  name, bg, panel, rail, on, onClick, disabled,
}: { name: ReactNode; bg: string; panel: string; rail: string; on: boolean; onClick?: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      disabled={disabled}
      onClick={onClick}
      className={cn("flex flex-col gap-2.5 rounded-xl bg-tm-inner p-3 text-left text-dracula-fg disabled:cursor-not-allowed disabled:opacity-60", choiceBorder(on))}
    >
      <span className="flex h-[84px] gap-1.5 rounded-lg p-2" style={{ background: bg }} aria-hidden>
        <span className="w-3.5 rounded" style={{ background: rail }} />
        <span className="flex-1 rounded" style={{ background: panel }} />
        <span className="flex-[2] rounded" style={{ background: panel }} />
      </span>
      <span className="flex items-center gap-2 font-semibold">{name}</span>
    </button>
  );
}

function SettingRow({ title, sub, children }: { title: ReactNode; sub: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-tm-line py-3">
      <div className="flex flex-col gap-0.5">
        <span className="flex items-center gap-2 font-semibold">{title}</span>
        <span className="text-xs text-tm-muted">{sub}</span>
      </div>
      {children}
    </div>
  );
}

export default function AppearanceSettingsPage() {
  const { chartTheme, setChartTheme } = useThemeStore();
  const {
    textSize, setTextSize, highContrast, setHighContrast,
    reduceMotion, setReduceMotion, monoNumbers, setMonoNumbers, priceFlash, setPriceFlash,
  } = useA11yStore();
  const motionReduced = useReducedMotion();

  const marketName = MARKET_COLORS.find((m) => m.key === chartTheme)?.name ?? CHART_THEMES[chartTheme]?.label ?? "—";

  return (
    <TerminalPage
      title="화면 설정"
      crumb="설정"
      stats={[
        { label: "테마", value: highContrast ? "고대비 다크" : "다크 (Dracula)" },
        { label: "시세 색상", value: marketName },
        { label: "글자 크기", value: TEXT_SIZES[textSize]?.label ?? "—" },
      ]}
    >
      <PanelRow>
        <SettingsNav />
        <Panel tabs={["화면 설정"]} actions={[]} closable={false} className="flex-[999_1_600px]" bodyClassName="p-5">
          <H2>테마</H2>
          <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}>
            <ThemeCard name="다크 (Dracula)" bg="#1b1c24" panel="#282a36" rail="#343746" on={!highContrast} onClick={() => setHighContrast(false)} />
            {/* 앱은 다크 전용(ThemeProvider forcedTheme="dark") — 시스템 테마 추종은 아직 없다 */}
            <ThemeCard name={<>시스템 설정 따르기 <PreviewTag /></>} bg="#2a2b33" panel="#3a3c48" rail="#4a4c58" on={false} disabled />
            {/* 고대비 = a11yStore.highContrast → html[data-contrast="high"] — globals.css가 tm-* 표면을 검정 쪽으로, 뮤트 글자·테두리를 밝게 덮는다. 미리보기 색도 그 값 */}
            <ThemeCard name="고대비 다크" bg="#000000" panel="#14141a" rail="#2e2f3d" on={highContrast} onClick={() => setHighContrast(true)} />
          </div>

          <H2 sub="차트·호가·등락률 전체에 적용">시세 색상</H2>
          <div className="flex flex-wrap gap-2">
            {MARKET_COLORS.map((m) => {
              const t = CHART_THEMES[m.key];
              const on = chartTheme === m.key;
              return (
                <button
                  key={m.key}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setChartTheme(m.key)}
                  className={cn("flex flex-[1_1_200px] items-center gap-3.5 rounded-xl bg-tm-inner p-3.5 text-left text-dracula-fg", choiceBorder(on))}
                >
                  <svg width="44" height="36" viewBox="0 0 44 36" aria-hidden className="flex-none">
                    <line x1="10" x2="10" y1="4" y2="32" stroke={t.wickUp} />
                    <rect x="5" y="9" width="10" height="16" rx="1" fill={t.upColor} />
                    <line x1="32" x2="32" y1="6" y2="32" stroke={t.wickDown} />
                    <rect x="27" y="12" width="10" height="14" rx="1" fill={t.downColor} />
                  </svg>
                  <span className="flex flex-col gap-0.5">
                    <span className="font-semibold">{m.name}</span>
                    <span className="text-xs text-tm-muted">{m.sub}</span>
                  </span>
                </button>
              );
            })}
          </div>

          <H2>글자 크기</H2>
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex flex-wrap gap-0.5 rounded-lg bg-tm-inner p-[3px]" role="group" aria-label="글자 크기">
              {(Object.entries(TEXT_SIZES) as [TextSize, { label: string }][]).map(([v, t]) => {
                const on = textSize === v;
                return (
                  <button
                    key={v}
                    type="button"
                    aria-pressed={on}
                    onClick={() => setTextSize(v)}
                    className={cn("h-8 whitespace-nowrap rounded-md px-3 text-13", on ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg")}
                  >
                    {t.label}
                  </button>
                );
              })}
            </div>
          </div>
          <p className="m-0 rounded-[10px] bg-tm-inner p-3.5 text-tm-soft">미리보기: 이 문장의 크기가 선택한 글자 크기로 바뀝니다.</p>

          <H2>접근성</H2>
          <div>
            <SettingRow
              title="움직임 줄이기"
              sub={
                <>
                  차트 애니메이션과 실시간 깜빡임을 끕니다
                  {reduceMotion === null ? " · 시스템 설정을 따르는 중" : (
                    <>
                      {" · "}
                      <button type="button" onClick={() => setReduceMotion(null)} className="text-dracula-purple underline-offset-2 hover:underline">
                        시스템 설정 따르기
                      </button>
                    </>
                  )}
                </>
              }
            >
              <Toggle checked={motionReduced} label="움직임 줄이기" onChange={setReduceMotion} />
            </SettingRow>
            <SettingRow title="숫자 고정폭 글꼴" sub="가격·수량을 고정폭 글꼴로 정렬합니다. 끄면 본문 글꼴에 숫자 폭만 맞춥니다">
              <Toggle checked={monoNumbers} label="숫자 고정폭 글꼴" onChange={setMonoNumbers} />
            </SettingRow>
            <SettingRow
              title="가격 변동 깜빡임"
              sub={motionReduced ? "움직임 줄이기가 켜져 있어 깜빡이지 않습니다" : "실시간 가격이 바뀌면 잠깐 상승·하락 색으로 강조합니다"}
            >
              <Toggle checked={priceFlash} label="가격 변동 깜빡임" onChange={setPriceFlash} />
            </SettingRow>
          </div>
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
