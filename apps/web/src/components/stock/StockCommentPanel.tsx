"use client";

import { useEffect, useState } from "react";
import { Btn, IconBtn, SelectBox } from "@/components/terminal";
import { Muted, SkeletonRows } from "./parts";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";
import type { StockComment } from "@monticker/types";

interface EventOption { id: number; title: string; }

interface Props {
  stockId: number;
  /** 테두리/제목 없이 내용만 렌더링 (탭 전환형 컨테이너에 임베드할 때) */
  bare?: boolean;
}

function currentUserId(): number | null {
  const token = getAccessToken();
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split(".")[1]));
    return Number(payload.sub);
  } catch {
    return null;
  }
}

export default function StockCommentPanel({ stockId, bare = false }: Props) {
  const { isLoggedIn } = useAuth();
  const { toast } = useToast();
  const [comments, setComments] = useState<StockComment[]>([]);
  const [loading, setLoading] = useState(true);
  const [eventOptions, setEventOptions] = useState<EventOption[]>([]);
  const [content, setContent] = useState("");
  const [selectedEventId, setSelectedEventId] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    fetch(`/api/stocks/${stockId}/comments?size=50`)
      .then(res => (res.ok ? res.json() : []))
      .then((data: StockComment[]) => setComments(data))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    fetch(`/api/stocks/${stockId}/events`)
      .then(res => (res.ok ? res.json() : []))
      .then((data: EventOption[]) => setEventOptions(data.slice(0, 10)))
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stockId]);

  const submit = async () => {
    if (!content.trim() || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await authFetch(`/api/stocks/${stockId}/comments`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content, eventId: selectedEventId ? Number(selectedEventId) : undefined }),
      });
      if (!res.ok) {
        const e = await res.json().catch(() => null);
        throw new Error(e?.message ?? "댓글 작성에 실패했습니다.");
      }
      setContent("");
      setSelectedEventId("");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (id: number) => {
    await authFetch(`/api/stocks/${stockId}/comments/${id}`, { method: "DELETE" });
    load();
  };

  const report = async (id: number) => {
    const reason = window.prompt("신고 사유를 입력해주세요.");
    if (!reason || !reason.trim()) return;
    try {
      const res = await authFetch(`/api/stocks/${stockId}/comments/${id}/report`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason }),
      });
      if (!res.ok) {
        const e = await res.json().catch(() => null);
        throw new Error(e?.message ?? "신고에 실패했습니다.");
      }
      toast({ type: "success", title: "신고 접수됨", message: "신고가 접수되었습니다." });
    } catch (e) {
      toast({ type: "error", title: "신고 실패", message: (e as Error).message });
    }
  };

  const myUserId = currentUserId();

  const content_ = (
    <>
      {!bare && <h3 className="m-0 mb-2 text-15 font-bold text-dracula-fg">토론</h3>}

      {isLoggedIn ? (
        <div className="mb-3 flex flex-col gap-2">
          <textarea
            value={content}
            onChange={e => setContent(e.target.value)}
            aria-label="댓글 내용"
            placeholder="이 종목에 대한 의견을 남겨주세요 (매수·매도 권유는 게시할 수 없습니다)"
            rows={2}
            maxLength={500}
            className="w-full resize-none rounded-lg border border-tm-line bg-tm-inner px-3 py-2 text-13 text-dracula-fg outline-none placeholder:text-[#8b92b8] focus:border-dracula-purple"
          />
          <div className="flex items-center gap-2">
            {eventOptions.length > 0 && (
              <SelectBox
                aria-label="이벤트 태그"
                value={selectedEventId}
                onChange={e => setSelectedEventId(e.target.value)}
                className="min-h-9 flex-1 py-1"
              >
                <option value="">이벤트 태그 없음</option>
                {eventOptions.map(ev => (
                  <option key={ev.id} value={ev.id}>{ev.title}</option>
                ))}
              </SelectBox>
            )}
            <Btn kind="primary" size="sm" onClick={submit} disabled={submitting || !content.trim()} className="h-9 px-4">
              {submitting ? "게시 중..." : "게시"}
            </Btn>
          </div>
          {error && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{error}</p>}
        </div>
      ) : (
        <p className="m-0 mb-3 text-xs text-tm-muted">로그인 후 댓글을 작성할 수 있습니다.</p>
      )}

      {loading && <SkeletonRows n={3} h="h-14" />}

      {!loading && comments.length === 0 && <Muted>아직 댓글이 없습니다. 이 종목에 대한 첫 의견을 남겨보세요.</Muted>}

      {!loading && comments.length > 0 && (
        <ul className="m-0 list-none p-0">
          {comments.map(c => (
            <li key={c.id} className="border-b border-tm-line px-1 py-2.5">
              <div className="mb-1 flex items-center justify-between gap-2">
                <span className="text-xs font-semibold">{c.authorNickname}</span>
                <div className="flex items-center gap-1 text-2xs text-tm-muted">
                  <span className="num">{new Date(c.createdAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}</span>
                  {isLoggedIn && myUserId !== c.userId && (
                    <IconBtn name="flag" label="신고" size={24} iconSize={12} onClick={() => report(c.id)} />
                  )}
                  {myUserId === c.userId && (
                    <IconBtn name="trash" label="삭제" size={24} iconSize={12} onClick={() => remove(c.id)} />
                  )}
                </div>
              </div>
              <p className="m-0 whitespace-pre-wrap break-words text-13">{c.content}</p>
            </li>
          ))}
        </ul>
      )}
    </>
  );

  if (bare) return <div>{content_}</div>;
  return <div className="rounded-[10px] bg-tm-panel p-3.5">{content_}</div>;
}
