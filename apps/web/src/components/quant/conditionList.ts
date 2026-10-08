// 룰셋 빌더 조건 목록의 순서 조작 — 드래그앤드롭과 키보드(위·아래 버튼)가 같은 리듀서를 탄다.
// 화면에 보이는 목록이 상태 배열의 부분집합일 수 있어서(매도 조건은 손절·익절 칸이 따로 빠진다)
// 위치는 인덱스가 아니라 id 기준으로 지정한다.

export interface Identified { id: string }

export type DropPosition = "before" | "after";

export type ConditionListAction<T extends Identified> =
  /** targetId가 없으면 끝에 붙인다. */
  | { type: "insert"; item: T; targetId?: string | null; position?: DropPosition }
  | { type: "move"; id: string; targetId: string | null; position?: DropPosition }
  /** 키보드 이동 — scope는 화면에 보이는 순서의 id 목록(생략하면 전체). */
  | { type: "shift"; id: string; delta: -1 | 1; scope?: string[] }
  | { type: "update"; item: T }
  | { type: "remove"; id: string };

function placeAt<T extends Identified>(list: T[], item: T, targetId: string | null | undefined, position: DropPosition): T[] {
  const idx = targetId == null ? -1 : list.findIndex(x => x.id === targetId);
  if (idx < 0) return [...list, item];
  const at = position === "after" ? idx + 1 : idx;
  return [...list.slice(0, at), item, ...list.slice(at)];
}

export function conditionListReducer<T extends Identified>(list: T[], action: ConditionListAction<T>): T[] {
  switch (action.type) {
    case "insert":
      if (list.some(x => x.id === action.item.id)) return list;
      return placeAt(list, action.item, action.targetId, action.position ?? "before");
    case "move": {
      if (action.id === action.targetId) return list;
      const item = list.find(x => x.id === action.id);
      if (!item) return list;
      const rest = list.filter(x => x.id !== action.id);
      const next = placeAt(rest, item, action.targetId, action.position ?? "before");
      return next.every((x, i) => x === list[i]) ? list : next;
    }
    case "shift": {
      const scope = action.scope ?? list.map(x => x.id);
      const i = scope.indexOf(action.id);
      const neighbor = i < 0 ? undefined : scope[i + action.delta];
      if (neighbor === undefined) return list;
      return conditionListReducer(list, {
        type: "move", id: action.id, targetId: neighbor, position: action.delta < 0 ? "before" : "after",
      });
    }
    case "update":
      return list.map(x => (x.id === action.item.id ? action.item : x));
    case "remove":
      return list.filter(x => x.id !== action.id);
  }
}

/** 드래그 중 포인터가 행의 위쪽 절반이면 앞에, 아래쪽 절반이면 뒤에 놓는다. */
export function dropPositionFor(clientY: number, rect: { top: number; height: number }): DropPosition {
  return clientY < rect.top + rect.height / 2 ? "before" : "after";
}

/** HTML5 DnD 데이터 타입 — 다른 앱에서 끌어온 텍스트·파일은 받지 않는다. */
export const DND_MIME = "application/x-monticker-quant-block";
