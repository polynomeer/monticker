import { describe, expect, it } from "vitest";
import {
  INTEREST_SECTORS, INTEREST_SECTOR_KEYWORDS, interestFirst, interestOrderingActive, isInterestSector, matchingInterests,
  type InterestSector,
} from "@/lib/interestSectors";
import { matchesInterest } from "@/components/alerts/data";

describe("interest → sector mapping (ADR-099)", () => {
  it("defines keywords for every interest enum, in one place", () => {
    expect(Object.keys(INTEREST_SECTOR_KEYWORDS).sort()).toEqual(INTEREST_SECTORS.map((s) => s.key).sort());
  });

  it("matches KRX broad sectors and seed sub-sectors, ignoring spacing and middle dots", () => {
    expect(isInterestSector("전기전자", ["SEMICONDUCTOR"])).toBe(true);
    expect(isInterestSector("전기·전자", ["SEMICONDUCTOR"])).toBe(true);
    expect(isInterestSector("반도체·디스플레이", ["SEMICONDUCTOR"])).toBe(true);
    expect(isInterestSector("의약품", ["BIO"])).toBe(true);
    expect(isInterestSector("의료정밀", ["BIO"])).toBe(true);
    expect(isInterestSector("금융업", ["FINANCE"])).toBe(true);
    expect(isInterestSector("운수장비", ["AUTOMOTIVE"])).toBe(true);
    expect(isInterestSector("인터넷 플랫폼", ["INTERNET_PLATFORM"])).toBe(true);
    expect(isInterestSector("IT 서비스", ["INTERNET_PLATFORM"])).toBe(true);
    expect(isInterestSector("기계", ["SHIPBUILDING_DEFENSE"])).toBe(true);
  });

  it("does not match unrelated sectors, empty sectors or empty interests", () => {
    expect(isInterestSector("음식료품", ["SEMICONDUCTOR", "BIO", "FINANCE"])).toBe(false);
    expect(isInterestSector(null, ["BIO"])).toBe(false);
    expect(isInterestSector("", ["BIO"])).toBe(false);
    expect(isInterestSector("의약품", [])).toBe(false);
  });

  it("DIVIDEND and ETF are not sectors — they never match", () => {
    for (const s of ["전기전자", "금융업", "의약품", "ETF", "배당"]) {
      expect(isInterestSector(s, ["DIVIDEND", "ETF"])).toBe(false);
    }
  });

  it("returns every matching interest in the user's order (broad sectors overlap)", () => {
    expect(matchingInterests("전기전자", ["SECONDARY_BATTERY", "BIO", "SEMICONDUCTOR"])).toEqual(["SECONDARY_BATTERY", "SEMICONDUCTOR"]);
  });
});

describe("interestFirst", () => {
  type Row = { id: number; sector: string | null };
  const rows: Row[] = [
    { id: 1, sector: "음식료품" },
    { id: 2, sector: "의약품" },
    { id: 3, sector: null },
    { id: 4, sector: "금융업" },
    { id: 5, sector: "제약" },
    { id: 6, sector: "철강금속" },
  ];
  const sectorOf = (r: Row) => r.sector;

  it("moves interest items first, keeps both groups in their original order (stable)", () => {
    expect(interestFirst(rows, sectorOf, ["BIO"]).map((r) => r.id)).toEqual([2, 5, 1, 3, 4, 6]);
    expect(interestFirst(rows, sectorOf, ["FINANCE", "BIO"]).map((r) => r.id)).toEqual([2, 4, 5, 1, 3, 6]);
  });

  it("never drops or duplicates items", () => {
    const interests: InterestSector[][] = [[], ["BIO"], ["FINANCE", "BIO", "SEMICONDUCTOR"], ["DIVIDEND"]];
    for (const i of interests) {
      const out = interestFirst(rows, sectorOf, i);
      expect(out).toHaveLength(rows.length);
      expect([...out].sort((a, b) => a.id - b.id)).toEqual(rows);
    }
  });

  it("is a no-op (same order, new array) when switched off or without interests", () => {
    const off = interestFirst(rows, sectorOf, ["BIO"], false);
    expect(off).toEqual(rows);
    expect(off).not.toBe(rows);
    expect(interestFirst(rows, sectorOf, [])).toEqual(rows);
    expect(interestFirst(rows, sectorOf, ["ETF"])).toEqual(rows);
  });

  it("does not mutate its input", () => {
    const copy = [...rows];
    interestFirst(rows, sectorOf, ["BIO"]);
    expect(rows).toEqual(copy);
  });
});

describe("interestOrderingActive", () => {
  it("is on only with at least one interest and the switch not turned off", () => {
    expect(interestOrderingActive(null)).toBe(false);
    expect(interestOrderingActive({ interestSectors: [] })).toBe(false);
    expect(interestOrderingActive({ interestSectors: ["BIO"] })).toBe(true); // 이전 응답(스위치 필드 없음) = 기본 켜짐
    expect(interestOrderingActive({ interestSectors: ["BIO"], interestOrdering: true })).toBe(true);
    expect(interestOrderingActive({ interestSectors: ["BIO"], interestOrdering: false })).toBe(false);
  });
});

describe("alerts — matchesInterest", () => {
  const sectors: Record<number, string> = { 1: "의약품", 2: "음식료품" };
  const sectorOf = (id: number) => sectors[id];

  it("keeps alerts whose stock sector is an interest; stock-less or unknown stocks never match", () => {
    expect(matchesInterest({ stockId: 1 }, sectorOf, ["BIO"])).toBe(true);
    expect(matchesInterest({ stockId: 2 }, sectorOf, ["BIO"])).toBe(false);
    expect(matchesInterest({ stockId: null }, sectorOf, ["BIO"])).toBe(false);
    expect(matchesInterest({ stockId: 99 }, sectorOf, ["BIO"])).toBe(false);
  });
});
