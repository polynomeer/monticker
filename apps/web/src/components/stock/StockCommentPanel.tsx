"use client";

import { useEffect, useState } from "react";
import { ChatCircle, Flag, Trash } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Skeleton } from "@/components/ui/Skeleton";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useAuth } from "@/hooks/useAuth";
import { useToast } from "@/hooks/useToast";
import type { StockComment } from "@monticker/types";

interface EventOption { id: number; title: string; }

interface Props {
  stockId: number;
  /** 카드 테두리/제목 없이 내용만 렌더링 (탭 전환형 컨테이너에 임베드할 때) */
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
      {!bare && <h3 className="font-semibold text-gray-900 dark:text-dracula-fg mb-3">커뮤니티</h3>}

      {isLoggedIn ? (
        <div className="mb-4 space-y-2">
          <textarea
            value={content}
            onChange={e => setContent(e.target.value)}
            placeholder="이 종목에 대한 의견을 남겨주세요 (매수·매도 권유는 게시할 수 없습니다)"
            rows={2}
            maxLength={500}
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm px-3 py-2 resize-none transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
          />
          <div className="flex items-center gap-2">
            {eventOptions.length > 0 && (
              <select
                value={selectedEventId}
                onChange={e => setSelectedEventId(e.target.value)}
                className="flex-1 rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-700 dark:text-dracula-fg text-xs px-2 py-1.5 transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
              >
                <option value="">이벤트 태그 없음</option>
                {eventOptions.map(ev => (
                  <option key={ev.id} value={ev.id}>{ev.title}</option>
                ))}
              </select>
            )}
            <button
              onClick={submit}
              disabled={submitting || !content.trim()}
              className="shrink-0 px-4 py-1.5 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-xs font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40"
            >
              {submitting ? "게시 중..." : "게시"}
            </button>
          </div>
          {error && <p className="text-xs text-dracula-red">{error}</p>}
        </div>
      ) : (
        <p className="mb-4 text-xs text-gray-400 dark:text-dracula-comment">로그인 후 댓글을 작성할 수 있습니다.</p>
      )}

      {loading && (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <Skeleton key={i} className="h-14 w-full rounded-lg" />)}
        </div>
      )}

      {!loading && comments.length === 0 && (
        <EmptyState icon={ChatCircle} title="아직 댓글이 없습니다" description="이 종목에 대한 첫 의견을 남겨보세요." />
      )}

      {!loading && comments.length > 0 && (
        <ul className="space-y-2">
          {comments.map(c => (
            <li key={c.id} className="p-3 rounded-lg border border-gray-200 dark:border-dracula-line/60 bg-gray-50 dark:bg-dracula-bg">
              <div className="flex items-center justify-between gap-2 mb-1">
                <span className="text-xs font-semibold text-gray-700 dark:text-dracula-fg">{c.authorNickname}</span>
                <div className="flex items-center gap-2 text-[11px] text-gray-400 dark:text-dracula-comment">
                  <span>{new Date(c.createdAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}</span>
                  {isLoggedIn && myUserId !== c.userId && (
                    <button onClick={() => report(c.id)} title="신고" className="hover:text-dracula-red transition-colors">
                      <Flag size={12} weight="bold" aria-hidden />
                    </button>
                  )}
                  {myUserId === c.userId && (
                    <button onClick={() => remove(c.id)} title="삭제" className="hover:text-dracula-red transition-colors">
                      <Trash size={12} weight="bold" aria-hidden />
                    </button>
                  )}
                </div>
              </div>
              <p className="text-sm text-gray-900 dark:text-dracula-fg whitespace-pre-wrap break-words">{c.content}</p>
            </li>
          ))}
        </ul>
      )}
    </>
  );

  if (bare) return <div>{content_}</div>;
  return <Card className="p-4">{content_}</Card>;
}
