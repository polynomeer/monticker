"use client";

import { Prohibit } from "@phosphor-icons/react";
import { useTradingStatus } from "@/hooks/useBrokerage";

/**
 * ADR-057 — 실거래 주문 킬 스위치가 켜져 있으면 주문 화면 맨 위에 알린다. 서버는 이 상태에서 주문을 423으로 거부하지만,
 * 사용자가 주문을 다 채운 뒤에야 알게 하지 않는다. [note]는 화면별 보충 설명(조건부 주문은 일시정지 등).
 */
export function TradingHaltBanner({ enabled, note }: { enabled: boolean; note?: string }) {
  const { data } = useTradingStatus(enabled);
  if (!data?.halted) return null;
  return (
    <div role="alert" className="flex items-start gap-2 rounded-lg border border-dracula-red/40 bg-dracula-red/10 p-3 mb-5">
      <Prohibit size={18} weight="bold" className="text-dracula-red shrink-0 mt-0.5" aria-hidden />
      <div>
        <p className="text-sm font-semibold text-dracula-red">실거래 주문이 일시 중단되었습니다</p>
        <p className="text-xs text-dracula-red/80 mt-0.5">{data.message}</p>
        {note && <p className="text-xs text-dracula-red/80 mt-0.5">{note}</p>}
      </div>
    </div>
  );
}
