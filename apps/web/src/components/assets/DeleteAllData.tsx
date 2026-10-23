"use client";

import { useState } from "react";
import { Btn, Notice } from "@/components/terminal";
import { useDeleteAllPortfolioData } from "@/hooks/usePortfolio";

/**
 * 내 자산 데이터 전체 삭제 = "계좌 정보 수집·이용" 동의 철회. 되돌릴 수 없어 두 번 누르게 한다(DisconnectPanel과 같은 방식).
 * 모의계좌·증권사 주문 연동은 그대로다.
 */
export function DeleteAllData({ onDone }: { onDone?: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const del = useDeleteAllPortfolioData();
  if (!confirming) {
    return <Btn kind="danger" size="sm" icon="trash" onClick={() => setConfirming(true)}>내 자산 데이터 전체 삭제</Btn>;
  }
  return (
    <Notice tone="danger">
      모든 계좌·거래·가져오기 기록을 지우고 계좌 정보 수집·이용 동의를 철회합니다. 되돌릴 수 없습니다. 모의계좌와 증권사 주문 연동은 그대로입니다.
      {del.isError && <p className="m-0 mt-1.5">{del.error instanceof Error ? del.error.message : "지우지 못했습니다."}</p>}
      <div className="mt-2.5 flex gap-2">
        <Btn kind="ghost" size="sm" onClick={() => { setConfirming(false); del.reset(); }}>취소</Btn>
        <Btn
          kind="danger" size="sm" disabled={del.isPending}
          onClick={() => del.mutate(undefined, { onSuccess: () => { setConfirming(false); onDone?.(); } })}
        >
          {del.isPending ? "지우는 중…" : "모두 지우고 동의 철회"}
        </Btn>
      </div>
    </Notice>
  );
}
