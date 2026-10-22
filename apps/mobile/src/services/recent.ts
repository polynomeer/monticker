import { useCallback, useEffect, useState } from "react";
import AsyncStorage from "@react-native-async-storage/async-storage";

/** 검색 화면의 최근 검색어·최근 본 종목 — 기기에만 둔다(웹도 브라우저 저장) */

const QUERIES = "monticker.recent.queries";
const STOCKS = "monticker.recent.stocks";
const MAX = 6;

export interface RecentStock { symbol: string; name: string }

async function read<T>(key: string): Promise<T[]> {
  try {
    const raw = await AsyncStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T[]) : [];
  } catch {
    return [];
  }
}

async function push<T>(key: string, item: T, same: (a: T, b: T) => boolean): Promise<T[]> {
  const next = [item, ...(await read<T>(key)).filter((x) => !same(x, item))].slice(0, MAX);
  await AsyncStorage.setItem(key, JSON.stringify(next)).catch(() => undefined);
  return next;
}

export function rememberQuery(q: string) {
  const query = q.trim();
  if (query) return push<string>(QUERIES, query, (a, b) => a === b);
}

export function rememberStock(stock: RecentStock) {
  return push<RecentStock>(STOCKS, stock, (a, b) => a.symbol === b.symbol);
}

export function useRecent() {
  const [queries, setQueries] = useState<string[]>([]);
  const [stocks, setStocks] = useState<RecentStock[]>([]);
  const reload = useCallback(() => {
    read<string>(QUERIES).then(setQueries);
    read<RecentStock>(STOCKS).then(setStocks);
  }, []);
  useEffect(reload, [reload]);
  const clearStocks = useCallback(() => {
    AsyncStorage.removeItem(STOCKS).catch(() => undefined);
    setStocks([]);
  }, []);
  return { queries, stocks, reload, clearStocks };
}
