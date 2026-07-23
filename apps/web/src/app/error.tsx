"use client";

import { useEffect } from "react";

export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error("[GlobalError]", error);
  }, [error]);

  return (
    <div className="min-h-screen flex flex-col items-center justify-center gap-6 bg-[#0d1117] text-[#f8f8f2]">
      <div className="text-center space-y-2">
        <p className="text-4xl">⚠️</p>
        <h1 className="text-xl font-bold">문제가 발생했습니다</h1>
        <p className="text-sm text-[#6272a4]">{error.message || "알 수 없는 오류입니다."}</p>
        {error.digest && <p className="text-xs text-[#44475a]">ID: {error.digest}</p>}
      </div>
      <button
        onClick={reset}
        className="px-5 py-2 rounded-lg bg-[#bd93f9] text-[#282a36] font-semibold text-sm hover:bg-[#ff79c6] transition-colors"
      >
        다시 시도
      </button>
    </div>
  );
}
