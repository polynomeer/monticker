/** 패널의 "내보내기" 아이콘 — 지금 화면에 보이는 표를 CSV로 내려받는다(브라우저 안에서만 만든다). */

export type CsvCell = string | number | null | undefined;

// 엑셀·시트가 수식으로 해석하는 첫 글자(CSV/수식 주입, OWASP). 종목명·거부 사유처럼 서버·증권사에서 온 문자열이
// "=HYPERLINK(...)"로 시작하면 파일을 연 사람의 PC에서 수식이 실행된다.
const FORMULA_PREFIX = /^[=+\-@\t\r]/;
// 부호 붙은 순수 십진수("-3.20", "+1.5%")는 수식이 아니라 값이다 — 엑셀에서 숫자로 쓸 수 있게 그대로 둔다.
const PLAIN_SIGNED_NUMBER = /^[+-]\d+(\.\d+)?%?$/;

/**
 * 한 칸을 CSV 문자열로.
 * - 문자열이 수식 시작 문자(= + - @ 탭 CR)로 시작하면 작은따옴표를 앞에 붙여 텍스트로 고정한다.
 *   예외는 부호 붙은 순수 숫자 문자열뿐이다(그 밖의 글자가 하나라도 있으면 막는다).
 * - 숫자 타입은 그대로 둔다 — 우리가 계산한 유한한 숫자(-1200 같은 손익)는 수식이 될 수 없다. NaN·Infinity는 빈칸.
 * - 쉼표·따옴표·줄바꿈(CR 포함)이 있으면 따옴표로 감싼다.
 */
export function csvCell(v: CsvCell): string {
  if (v == null) return "";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "";
  let s = String(v);
  if (FORMULA_PREFIX.test(s) && !PLAIN_SIGNED_NUMBER.test(s)) s = `'${s}`;
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** 머리글 + 행 → CSV 본문(BOM 없음). 줄바꿈은 CRLF(RFC 4180, 엑셀 기본). */
export function toCsv(headers: string[], rows: CsvCell[][]): string {
  return [headers, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n");
}

/** 파일 이름용 KST 날짜(YYYYMMDD) — 브라우저 시간대와 무관하게 한국 날짜. */
export function kstDateStamp(now: Date = new Date()): string {
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${kst.getUTCFullYear()}${p(kst.getUTCMonth() + 1)}${p(kst.getUTCDate())}`;
}

export function downloadCsv(filename: string, headers: string[], rows: CsvCell[][]) {
  // 엑셀이 한글을 깨뜨리지 않도록 BOM을 붙인다
  const blob = new Blob(["﻿" + toCsv(headers, rows)], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
