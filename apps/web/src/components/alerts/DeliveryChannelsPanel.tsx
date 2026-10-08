"use client";

import { Panel } from "@/components/terminal";
import { CATEGORY_LABEL, channelLabels, quietHoursLabel, sortCategories, type DeliveryChannels } from "@/components/alerts/channels";

/**
 * ADR-093 — 알림 화면 "전달 채널". 서버가 worker 발송 정책과 같은 규칙으로 계산한 종류별 채널을 그대로 보여준다
 * (화면에서 규칙을 다시 계산하지 않는다).
 */
export default function DeliveryChannelsPanel({ data, loading }: { data: DeliveryChannels | null | undefined; loading: boolean }) {
  return (
    <Panel tabs={["전달 채널"]} actions={[]} closable={false} className="flex-[1_1_320px]">
      {loading ? (
        <div className="h-24 animate-pulse rounded-lg bg-tm-inner" />
      ) : !data ? (
        <p className="py-6 text-center text-13 text-tm-muted">전달 채널을 불러오지 못했습니다.</p>
      ) : (
        <>
          <div className="flex items-center justify-between border-b border-tm-line pb-2.5 text-xs">
            <span className="text-tm-muted">방해 금지 시간</span>
            <span className={`num ${data.quietHours.activeNow ? "text-dracula-orange" : "text-tm-soft"}`}>{quietHoursLabel(data.quietHours)}</span>
          </div>
          <ul className="m-0 list-none p-0">
            {sortCategories(data.categories).map((c) => {
              const labels = channelLabels(c);
              return (
                <li key={c.category} className="flex items-start justify-between gap-3 border-b border-tm-line py-2.5">
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className="text-13 font-semibold">{CATEGORY_LABEL[c.category] ?? c.category}</span>
                    {c.alwaysOn && <span className="text-2xs text-dracula-green">항상 받음 · 방해 금지 시간에도 즉시</span>}
                    {!c.alwaysOn && data.quietHours.enabled && labels.includes("푸시") && (
                      <span className="text-2xs text-tm-muted">방해 금지 시간에는 푸시하지 않음</span>
                    )}
                    {c.category === "STRATEGY_MARKET" && !data.marketingAgreed && (
                      <span className="text-2xs text-tm-muted">마케팅 수신 동의가 없어 보내지 않음</span>
                    )}
                  </span>
                  <span className="flex flex-wrap justify-end gap-1">
                    {labels.length === 0 ? (
                      <span className="text-xs text-tm-muted">받지 않음</span>
                    ) : labels.map((l) => (
                      <span key={l} className="inline-flex h-[22px] items-center rounded-md bg-tm-raised px-2 text-2xs text-tm-soft">{l}</span>
                    ))}
                  </span>
                </li>
              );
            })}
          </ul>
          <span className="text-2xs text-tm-muted">카카오 알림톡은 연동 예정입니다.</span>
        </>
      )}
    </Panel>
  );
}
