"use client";

import { Btn, Notice } from "@/components/terminal";

interface Props {
  groupName: string;
  itemCount: number;
  /** 이 그룹을 대상으로 한 켜진 Watch Rule 수. 아직 모르면(불러오는 중·실패) null */
  activeRuleCount: number | null;
  pending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * 관심종목 그룹 삭제 확인 — DELETE /api/watchlists/groups/{id}.
 * 서버는 항목을 함께 지우고, 이 그룹을 대상으로 한 Watch Rule을 끈다(ADR-095 V92 트리거 — 규칙·발동 기록은 남는다).
 * 되돌릴 수 없는 동작이라 한 번 더 묻고, 꺼질 규칙을 미리 알린다.
 */
export default function GroupDeleteConfirm({ groupName, itemCount, activeRuleCount, pending, onConfirm, onCancel }: Props) {
  return (
    <div role="alertdialog" aria-labelledby="group-delete-title" aria-describedby="group-delete-desc" className="px-2">
      <Notice tone="danger" icon="trash">
        <p id="group-delete-title" className="m-0 font-semibold">
          ‘{groupName}’ 그룹을 삭제할까요?
        </p>
        <div id="group-delete-desc" className="mt-1 flex flex-col gap-1 text-xs">
          <p className="m-0">담긴 종목 {itemCount}개도 함께 지워지며 되돌릴 수 없습니다.</p>
          <p className="m-0">
            이 그룹을 대상으로 한 Watch Rule은 자동으로 꺼집니다
            {activeRuleCount != null && activeRuleCount > 0 && <> — 지금 켜져 있는 규칙 <b className="num">{activeRuleCount}개</b></>}
            . 규칙과 발동 기록은 남습니다.
          </p>
        </div>
        <div className="mt-2 flex gap-2">
          <Btn kind="danger" size="sm" onClick={onConfirm} disabled={pending}>
            {pending ? "삭제 중…" : "그룹 삭제"}
          </Btn>
          <Btn kind="ghost" size="sm" onClick={onCancel} disabled={pending}>취소</Btn>
        </div>
      </Notice>
    </div>
  );
}
