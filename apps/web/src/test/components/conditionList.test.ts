import { describe, it, expect } from "vitest";
import { conditionListReducer, dropPositionFor } from "@/components/quant/conditionList";

type Item = { id: string; v?: number };
const list: Item[] = [{ id: "a" }, { id: "b" }, { id: "c" }, { id: "d" }];
const ids = (l: Item[]) => l.map(x => x.id).join("");

describe("conditionListReducer — 룰셋 빌더 조건 순서", () => {
  it("move: 대상 앞에 놓는다", () => {
    expect(ids(conditionListReducer(list, { type: "move", id: "d", targetId: "b" }))).toBe("adbc");
  });

  it("move: 대상 뒤에 놓는다", () => {
    expect(ids(conditionListReducer(list, { type: "move", id: "a", targetId: "c", position: "after" }))).toBe("bcad");
  });

  it("move: targetId가 null이면 끝으로 보낸다", () => {
    expect(ids(conditionListReducer(list, { type: "move", id: "a", targetId: null }))).toBe("bcda");
  });

  it("move: 자기 자신이나 제자리로 옮기면 같은 배열을 돌려준다(불필요한 리렌더 방지)", () => {
    expect(conditionListReducer(list, { type: "move", id: "b", targetId: "b" })).toBe(list);
    expect(conditionListReducer(list, { type: "move", id: "b", targetId: "c" })).toBe(list);
    expect(conditionListReducer(list, { type: "move", id: "b", targetId: "a", position: "after" })).toBe(list);
  });

  it("move: 없는 id는 무시한다", () => {
    expect(conditionListReducer(list, { type: "move", id: "zz", targetId: "a" })).toBe(list);
  });

  it("insert: 팔레트 블록을 원하는 자리에 넣는다", () => {
    expect(ids(conditionListReducer(list, { type: "insert", item: { id: "n" }, targetId: "c" }))).toBe("abncd");
    expect(ids(conditionListReducer(list, { type: "insert", item: { id: "n" }, targetId: "c", position: "after" }))).toBe("abcnd");
    expect(ids(conditionListReducer(list, { type: "insert", item: { id: "n" } }))).toBe("abcdn");
    expect(ids(conditionListReducer([], { type: "insert", item: { id: "n" }, targetId: "x" }))).toBe("n");
  });

  it("insert: 같은 id가 이미 있으면 두 번 넣지 않는다", () => {
    expect(conditionListReducer(list, { type: "insert", item: { id: "a" } })).toBe(list);
  });

  it("shift: 위·아래 한 칸, 끝에서는 그대로", () => {
    expect(ids(conditionListReducer(list, { type: "shift", id: "c", delta: -1 }))).toBe("acbd");
    expect(ids(conditionListReducer(list, { type: "shift", id: "b", delta: 1 }))).toBe("acbd");
    expect(conditionListReducer(list, { type: "shift", id: "a", delta: -1 })).toBe(list);
    expect(conditionListReducer(list, { type: "shift", id: "d", delta: 1 })).toBe(list);
  });

  it("shift: 화면에 보이는 부분집합(scope) 기준으로 이웃을 찾는다 — 매도 쪽 손절·익절 칸은 건너뛴다", () => {
    // b, d만 화면에 보이고 a, c는 손절·익절 칸으로 따로 그려지는 상황
    const scope = ["b", "d"];
    expect(ids(conditionListReducer(list, { type: "shift", id: "d", delta: -1, scope }))).toBe("adbc");
    expect(ids(conditionListReducer(list, { type: "shift", id: "b", delta: 1, scope }))).toBe("acdb");
    expect(conditionListReducer(list, { type: "shift", id: "b", delta: -1, scope })).toBe(list);
  });

  it("update · remove", () => {
    const updated = conditionListReducer(list, { type: "update", item: { id: "b", v: 7 } });
    expect(updated[1]).toEqual({ id: "b", v: 7 });
    expect(ids(conditionListReducer(list, { type: "remove", id: "c" }))).toBe("abd");
  });
});

describe("dropPositionFor", () => {
  it("행 위쪽 절반이면 before, 아래쪽 절반이면 after", () => {
    const rect = { top: 100, height: 40 };
    expect(dropPositionFor(105, rect)).toBe("before");
    expect(dropPositionFor(119, rect)).toBe("before");
    expect(dropPositionFor(120, rect)).toBe("after");
    expect(dropPositionFor(139, rect)).toBe("after");
  });
});
