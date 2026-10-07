"use client";

import { useEffect } from "react";
import { Btn, BtnLink } from "@/components/terminal";
import { CenteredPage, StatusCard } from "@/components/auth/StatusCard";

export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error("[GlobalError]", error);
  }, [error]);

  return (
    <CenteredPage>
      <StatusCard
        tone="error"
        title="문제가 발생했습니다"
        actions={
          <>
            <Btn size="lg" full onClick={reset}>다시 시도</Btn>
            <BtnLink href="/" kind="ghost" size="lg" full>홈으로</BtnLink>
          </>
        }
      >
        <p className="m-0">{error.message || "알 수 없는 오류입니다."}</p>
        {error.digest && <p className="num m-0 text-xs text-tm-muted">ID: {error.digest}</p>}
      </StatusCard>
    </CenteredPage>
  );
}
