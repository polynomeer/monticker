"use client";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Btn, BtnLink } from "@/components/terminal";
import { CenteredPage, StatusCard } from "@/components/auth/StatusCard";
import { unsubscribeWithToken, type UnsubscribeResult } from "@/services/unsubscribe";

const SETTINGS = "/settings/notifications";

/**
 * ADR-102 — 주간 투자 행동 리포트 메일의 "수신 거부" 링크가 여는 화면. 로그인 없이 쓴다.
 *
 * 링크를 여는 것(GET)만으로는 끄지 않는다 — 메일 보안 스캐너가 링크를 미리 열어 보기 때문이다. 사람이 버튼을 눌러야
 * 서버에 POST한다(메일 클라이언트의 원클릭 버튼은 이 화면을 거치지 않고 같은 API를 직접 부른다).
 */
function UnsubscribeContent() {
  const token = useSearchParams().get("token");
  const [state, setState] = useState<"idle" | "sending" | UnsubscribeResult>("idle");

  const submit = async () => {
    if (!token) return;
    setState("sending");
    setState(await unsubscribeWithToken(token));
  };

  if (!token || state === "invalid") {
    return (
      <StatusCard
        tone="error"
        title="유효하지 않은 링크입니다"
        actions={<BtnLink href={SETTINGS} size="lg" full>알림 설정에서 끄기</BtnLink>}
      >
        <p className="m-0">수신 거부 링크가 올바르지 않거나 일부가 잘렸습니다. 메일의 링크를 다시 눌러 주시거나, 로그인 후 알림 설정에서 끌 수 있습니다.</p>
      </StatusCard>
    );
  }

  if (state === "done") {
    return (
      <StatusCard
        tone="ok"
        title="수신 거부되었습니다"
        actions={<BtnLink href={SETTINGS} kind="ghost" size="lg" full>알림 설정 열기</BtnLink>}
      >
        <p className="m-0">이제 주간 투자 행동 리포트 이메일을 보내지 않습니다. 다른 알림 설정은 바뀌지 않았습니다.</p>
        <p className="m-0 text-13 text-tm-muted">다시 받으려면 알림 설정에서 ‘주간 투자 행동 리포트’를 켜세요.</p>
      </StatusCard>
    );
  }

  const sending = state === "sending";
  return (
    <StatusCard
      tone="warn"
      step="이메일 수신 거부"
      title="주간 투자 행동 리포트를 그만 받을까요?"
      actions={
        <>
          <Btn size="xl" full disabled={sending} onClick={submit} className="h-12">
            {sending ? "처리 중..." : "수신 거부"}
          </Btn>
          <BtnLink href={SETTINGS} kind="ghost" size="lg" full>알림 설정에서 관리하기</BtnLink>
        </>
      }
    >
      <p className="m-0">매주 월요일 보내 드리는 모의투자 행동 리포트 이메일만 끕니다. 로그인은 필요 없습니다.</p>
      {state === "retry" && (
        <p role="alert" className="m-0 text-13 text-[#ff8a8a]">잠시 후 다시 시도해 주세요. 계속 안 되면 알림 설정에서 끌 수 있습니다.</p>
      )}
    </StatusCard>
  );
}

export default function UnsubscribePage() {
  return (
    <CenteredPage>
      <Suspense fallback={null}>
        <UnsubscribeContent />
      </Suspense>
    </CenteredPage>
  );
}
