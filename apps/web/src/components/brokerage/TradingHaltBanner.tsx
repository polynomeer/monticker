"use client";

import { useTradingStatus } from "@/hooks/useBrokerage";
import { Notice } from "@/components/terminal";

/**
 * ADR-057 — 실거래 주문 킬 스위치가 켜져 있으면 주문 화면 맨 위에 알린다. 서버는 이 상태에서 주문을 423으로 거부하지만,
 * 사용자가 주문을 다 채운 뒤에야 알게 하지 않는다. [note]는 화면별 보충 설명(조건부 주문은 일시정지 등).
 */
export function TradingHaltBanner({ enabled, note }: { enabled: boolean; note?: string }) {
  const { data } = useTradingStatus(enabled);
  if (!data?.halted) return null;
  return (
    <Notice tone="danger" icon="x">
      <p className="m-0 text-sm font-semibold text-[#ff8a8a]">실거래 주문이 일시 중단되었습니다</p>
      <p className="m-0 mt-0.5 text-xs">{data.message}</p>
      {note && <p className="m-0 mt-0.5 text-xs">{note}</p>}
    </Notice>
  );
}
