"use client";

import { useState } from "react";
import { Btn, Notice, Panel } from "@/components/terminal";
import { useDisconnectBrokerage } from "@/hooks/useBrokerage";

/**
 * ADR-067 — 증권사 연동 해지. 두 번 눌러야 실행된다(되돌릴 수 없는 작업).
 * 서버가 결과가 열린 주문 때문에 거부하면(409) 그 사유를 그대로 보여준다.
 */
export default function DisconnectPanel({ onDone }: { onDone?: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const disconnect = useDisconnectBrokerage();

  return (
    <Panel tabs={["연동 해지"]} actions={[]} closable={false}>
      <p className="m-0 text-13 leading-relaxed text-tm-soft">
        해지하면 monticker에 저장된 App Key·App Secret·접근 토큰을 즉시 지우고, 대기 중인 조건부 주문을 모두 취소합니다. 주문·정산 기록은 남습니다.
      </p>
      <p className="m-0 text-xs leading-relaxed text-tm-muted">
        증권사 개발자센터에서 키 자체를 폐기하거나 재발급하면 더 안전합니다. 체결 여부를 아직 확인 중인 주문이 있으면 확인이 끝난 뒤에 해지할 수 있습니다.
      </p>

      {disconnect.isError && (
        <Notice tone="danger">{disconnect.error instanceof Error ? disconnect.error.message : "연동을 해지하지 못했습니다."}</Notice>
      )}
      {disconnect.isSuccess && (
        <Notice tone="ok">
          연동을 해지했습니다. 저장된 키를 지웠고 조건부 주문 {disconnect.data.cancelledConditionalOrders}건을 취소했습니다.
        </Notice>
      )}

      {!disconnect.isSuccess &&
        (confirming ? (
          <div className="flex flex-col gap-2 rounded-[10px] border border-[#64363f] bg-[#3d252b] p-3">
            <span className="text-13 font-semibold text-[#ff8a8a]">정말 해지할까요? 되돌릴 수 없습니다.</span>
            <div className="flex gap-2">
              <Btn kind="ghost" className="flex-1" onClick={() => setConfirming(false)} disabled={disconnect.isPending}>
                취소
              </Btn>
              <Btn
                kind="danger"
                className="flex-1"
                disabled={disconnect.isPending}
                onClick={() => disconnect.mutate(undefined, { onSuccess: () => onDone?.(), onSettled: () => setConfirming(false) })}
              >
                {disconnect.isPending ? "해지 중..." : "키 삭제하고 해지"}
              </Btn>
            </div>
          </div>
        ) : (
          <Btn kind="danger" full icon="trash" onClick={() => { disconnect.reset(); setConfirming(true); }}>
            연동 해지
          </Btn>
        ))}
    </Panel>
  );
}
