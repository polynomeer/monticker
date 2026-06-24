"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

export default function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime:            10_000,   // 10초 캐시 — 같은 키 중복 요청 방지
            refetchOnWindowFocus: false,    // 탭 전환 시 불필요한 재요청 방지
            retry:                1,
          },
        },
      })
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
