import { useCallback, useEffect, useRef, useState } from "react";
import { AppState } from "react-native";
import { getApiBase } from "./api";
import { authFetch } from "./auth";

/**
 * 화면용 GET 훅 — 웹의 react-query 대신 쓰는 작은 버전. path가 null이면 부르지 않는다.
 * refreshMs가 있으면 그 주기로, 그리고 앱이 앞으로 올 때 다시 읽는다. 다시 읽는 동안 이전 값을 그대로 보인다.
 */

export type ApiState<T> = {
  data: T | null;
  loading: boolean;
  error: boolean;
  /** 404 — 서버에 아직 기능이 없다(배포 전) */
  unavailable: boolean;
  reload: () => Promise<void>;
};

interface Options {
  auth?: boolean;
  refreshMs?: number;
}

export async function getJson<T>(path: string, auth = false): Promise<{ status: number; data: T | null }> {
  const res = auth ? await authFetch(path) : await fetch(`${getApiBase()}${path}`);
  if (!res.ok || res.status === 204) return { status: res.status, data: null };
  return { status: res.status, data: (await res.json()) as T };
}

export function useApi<T>(path: string | null, { auth = false, refreshMs }: Options = {}): ApiState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(path != null);
  const [error, setError] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const current = useRef(path);
  current.current = path;

  const reload = useCallback(async () => {
    if (path == null) { setData(null); setLoading(false); return; }
    try {
      const r = await getJson<T>(path, auth);
      if (current.current !== path) return;     // 그 사이 다른 주소로 바뀌었다
      setUnavailable(r.status === 404);
      setError(r.status >= 400 && r.status !== 404);
      setData(r.data);
    } catch {
      if (current.current === path) setError(true);
    } finally {
      if (current.current === path) setLoading(false);
    }
  }, [path, auth]);

  useEffect(() => {
    setLoading(path != null);
    reload();
    if (!refreshMs) return;
    const timer = setInterval(reload, refreshMs);
    const sub = AppState.addEventListener("change", (s) => { if (s === "active") reload(); });
    return () => { clearInterval(timer); sub.remove(); };
  }, [path, refreshMs, reload]);

  return { data, loading, error, unavailable, reload };
}
