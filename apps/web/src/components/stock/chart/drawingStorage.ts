/**
 * 차트 드로잉 로컬 저장 — 사용자·종목별, 이 브라우저의 localStorage에만(서버 동기화 없음).
 *
 * - 키: `monticker:chartDrawings:v2:{사용자 범위}:{종목 코드}`. 봉 간격과 무관하게 한 종목에 하나 —
 *   드로잉은 (시각, 가격)으로 저장돼 간격을 바꿔도 같은 자리에 다시 놓인다.
 * - 사용자 범위는 액세스 토큰의 `sub`를 해시한 값(로그아웃 상태는 "anon"). 식별자 원문을 키에 남기지 않는다.
 *   권한 경계가 아니라 같은 브라우저를 쓰는 계정끼리 드로잉이 섞이지 않게 하는 구분일 뿐이다.
 * - 값: `{ v: 2, drawings: Drawing[] }`. 읽을 때 형식을 검증하고, 쓸 때 개수·펜 점 수·글자 수·전체 크기를 자른다.
 * - 저장소 접근이 막혀도(사생활 보호 모드 등) 예외를 밖으로 던지지 않는다.
 */
import type { Drawing, DrawingKind, DrawingPoint } from "./types";
import { decimate } from "./drawingGeometry";

export const DRAWINGS_VERSION = 2;
export const DRAWING_LIMITS = {
  /** 종목당 드로잉 개수 — 넘으면 오래된 것부터 버린다 */
  maxDrawings: 100,
  /** 펜 한 획의 점 수 */
  maxPenPoints: 300,
  /** 텍스트 라벨 글자 수 */
  maxText: 120,
  /** 종목당 직렬화 크기(문자 수) */
  maxChars: 60_000,
} as const;

const KINDS: ReadonlySet<DrawingKind> = new Set(["TREND_LINE", "HORIZONTAL_LINE", "PEN", "TEXT"]);
const POINT_COUNT: Record<DrawingKind, [number, number]> = {
  HORIZONTAL_LINE: [1, 1],
  TEXT: [1, 1],
  TREND_LINE: [2, 2],
  PEN: [2, Number.MAX_SAFE_INTEGER],
};

type StorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function drawingsStorageKey(userScope: string, symbol: string): string {
  return `monticker:chartDrawings:v${DRAWINGS_VERSION}:${userScope}:${symbol}`;
}

/** FNV-1a 32비트 — 키 구분용(보안 목적 아님) */
function fnv1a(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0");
}

/** 액세스 토큰(JWT)의 sub로 사용자 범위를 만든다. 토큰이 없거나 읽을 수 없으면 "anon" */
export function userScopeFromToken(token: string | null | undefined): string {
  if (!token) return "anon";
  try {
    const part = token.split(".")[1];
    if (!part) return "anon";
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
    const payload = JSON.parse(atob(b64)) as { sub?: unknown };
    const sub = payload.sub;
    if (typeof sub !== "string" && typeof sub !== "number") return "anon";
    return `u${fnv1a(String(sub))}`;
  } catch {
    return "anon";
  }
}

function sanitizePoint(p: unknown): DrawingPoint | null {
  if (!p || typeof p !== "object") return null;
  const { time, price } = p as Record<string, unknown>;
  if (typeof time !== "number" || typeof price !== "number" || !Number.isFinite(time) || !Number.isFinite(price)) return null;
  return { time: Math.round(time), price };
}

/** 저장소에서 읽은 값 하나를 검증한다. 형식이 틀리면 null(지어내 채우지 않는다) */
export function sanitizeDrawing(raw: unknown): Drawing | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== "string" || r.id.length === 0 || r.id.length > 64) return null;
  if (typeof r.tool !== "string" || !KINDS.has(r.tool as DrawingKind)) return null;
  const tool = r.tool as DrawingKind;
  if (!Array.isArray(r.points)) return null;
  const points = r.points.map(sanitizePoint);
  if (points.some((p) => p == null)) return null;
  const [min, max] = POINT_COUNT[tool];
  if (points.length < min || points.length > Math.max(max, DRAWING_LIMITS.maxPenPoints * 10)) return null;
  const pts = tool === "PEN" ? decimate(points as DrawingPoint[], DRAWING_LIMITS.maxPenPoints) : (points as DrawingPoint[]).slice(0, max);
  if (tool === "TEXT") {
    if (typeof r.text !== "string") return null;
    const text = r.text.trim().slice(0, DRAWING_LIMITS.maxText);
    if (!text) return null;
    return { id: r.id, tool, points: pts, text };
  }
  return { id: r.id, tool, points: pts };
}

/** 검증 + 상한 적용. 개수·크기를 넘으면 오래된(앞쪽) 드로잉부터 버린다 */
export function capDrawings(list: unknown[]): Drawing[] {
  let out = list.map(sanitizeDrawing).filter((d): d is Drawing => d != null);
  if (out.length > DRAWING_LIMITS.maxDrawings) out = out.slice(out.length - DRAWING_LIMITS.maxDrawings);
  while (out.length > 0 && JSON.stringify({ v: DRAWINGS_VERSION, drawings: out }).length > DRAWING_LIMITS.maxChars) {
    out = out.slice(1);
  }
  return out;
}

export function loadDrawings(storage: StorageLike | null | undefined, key: string): Drawing[] {
  try {
    const raw = storage?.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as { v?: unknown; drawings?: unknown };
    if (parsed?.v !== DRAWINGS_VERSION || !Array.isArray(parsed.drawings)) return [];
    return capDrawings(parsed.drawings);
  } catch {
    return [];
  }
}

/** 상한을 적용해 저장하고, 실제로 남긴 목록을 돌려준다(화면도 이 목록을 쓴다). 저장 실패해도 목록은 돌려준다 */
export function saveDrawings(storage: StorageLike | null | undefined, key: string, drawings: Drawing[]): Drawing[] {
  const capped = capDrawings(drawings);
  try {
    if (capped.length === 0) storage?.removeItem(key);
    else storage?.setItem(key, JSON.stringify({ v: DRAWINGS_VERSION, drawings: capped }));
  } catch {
    /* 저장 공간 부족·접근 차단 — 화면 상태는 유지 */
  }
  return capped;
}

/**
 * 예전 형식(v1: `monticker:chartDrawings:{stockId}:{interval}`, 값은 Drawing[] 배열, 사용자 구분 없음)을
 * 새 키로 한 번 옮긴다. 새 키에 이미 값이 있으면 아무것도 하지 않는다. 옮긴 뒤 예전 키는 지운다.
 */
export function migrateLegacyDrawings(
  storage: StorageLike | null | undefined,
  stockId: number,
  intervals: readonly string[],
  targetKey: string,
): Drawing[] | null {
  try {
    if (!storage || storage.getItem(targetKey)) return null;
    const merged: unknown[] = [];
    const legacyKeys: string[] = [];
    for (const iv of intervals) {
      const k = `monticker:chartDrawings:${stockId}:${iv}`;
      const raw = storage.getItem(k);
      if (!raw) continue;
      legacyKeys.push(k);
      try {
        const arr = JSON.parse(raw);
        if (Array.isArray(arr)) merged.push(...arr);
      } catch { /* 깨진 값은 버린다 */ }
    }
    if (legacyKeys.length === 0) return null;
    const ids = new Set<string>();
    const unique = merged.filter((d) => {
      const id = (d as { id?: unknown })?.id;
      if (typeof id !== "string" || ids.has(id)) return false;
      ids.add(id);
      return true;
    });
    const saved = saveDrawings(storage, targetKey, unique as Drawing[]);
    for (const k of legacyKeys) storage.removeItem(k);
    return saved;
  } catch {
    return null;
  }
}

/** 차트 표시 설정(유형·자석) — 사용자 구분 없이 이 브라우저에 */
export const CHART_PREFS_KEY = "monticker:chartPrefs:v1";
