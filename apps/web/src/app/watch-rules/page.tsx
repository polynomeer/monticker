"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Robot } from "@phosphor-icons/react";
import type { WatchRuleResponse } from "@monticker/types";
import EmptyState from "@/components/common/EmptyState";
import { Card } from "@/components/ui/Card";
import { WatchRuleExecutionList } from "@/components/watchrule/WatchRuleExecutionList";
import { WatchRuleForm, type WatchRuleFormValue } from "@/components/watchrule/WatchRuleForm";
import { WatchRuleRow } from "@/components/watchrule/WatchRuleRow";
import {
  useCreateWatchRule,
  useDeleteWatchRule,
  useUpdateWatchRule,
  useWatchRuleExecutions,
  useWatchRules,
} from "@/hooks/useWatchRules";
import { useToast } from "@/hooks/useToast";
import { getAccessToken } from "@/services/auth";
import { ApiError } from "@/services/brokerage";

interface StockSummary { id: number; symbol: string; name: string; }

/**
 * ADR-051 — 이벤트 트리거 모의 자동주문 관리 화면.
 *
 * 모의투자 계좌 전용이다. 실전투자 계좌는 자동 주문하지 않는다(ADR-025/036) — 화면에서도
 * 그 경계를 분명히 말해야 사용자가 "실계좌도 자동으로 사겠지"라고 오해하지 않는다.
 */
export default function WatchRulesPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [stocks, setStocks] = useState<Map<number, StockSummary>>(new Map());
  const [tab, setTab] = useState<"rules" | "history">("rules");

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: rules, isLoading: rulesLoading } = useWatchRules(isLoggedIn);
  const { data: executions, isLoading: executionsLoading } = useWatchRuleExecutions(isLoggedIn);
  const create = useCreateWatchRule();
  const update = useUpdateWatchRule();
  const remove = useDeleteWatchRule();
  const { toast } = useToast();

  // 룰은 stockId 만 들고 있다 — 표시용 종목명을 한 번에 채운다.
  useEffect(() => {
    if (!rules?.length) return;
    const missing = [...new Set(rules.map((r) => r.stockId))].filter((id) => !stocks.has(id));
    if (!missing.length) return;
    let cancelled = false;
    Promise.all(
      missing.map((id) => fetch(`/api/stocks/${id}`).then((r) => (r.ok ? r.json() : null)).catch(() => null)),
    ).then((loaded) => {
      if (cancelled) return;
      setStocks((prev) => {
        const next = new Map(prev);
        loaded.forEach((s: StockSummary | null) => { if (s) next.set(s.id, s); });
        return next;
      });
    });
    return () => { cancelled = true; };
  }, [rules, stocks]);

  // stocks 가 바뀔 때만 새로 만든다 — 매 렌더마다 새 함수를 만들면 아래 useMemo 가 매번 재계산된다.
  const stockLabel = useCallback(
    (stockId: number) => {
      const s = stocks.get(stockId);
      return s ? `${s.name} (${s.symbol})` : `종목 #${stockId}`;
    },
    [stocks],
  );

  const ruleLabels = useMemo(() => {
    const map = new Map<number, { stockLabel: string; rule: WatchRuleResponse }>();
    rules?.forEach((r) => map.set(r.id, { stockLabel: stockLabel(r.stockId), rule: r }));
    return map;
  }, [rules, stockLabel]);

  const fail = (title: string) => (e: unknown) =>
    toast({ type: "error", title, message: e instanceof ApiError ? e.message : "잠시 후 다시 시도해주세요." });

  const handleCreate = (value: WatchRuleFormValue) =>
    create.mutate(value, {
      onSuccess: () => toast({ type: "success", title: "규칙 생성됨", message: "이벤트가 감지되면 자동으로 주문합니다." }),
      onError: fail("규칙 생성 실패"),
    });

  const handleToggle = (rule: WatchRuleResponse) =>
    update.mutate(
      { ruleId: rule.id, req: { isActive: !rule.isActive } },
      {
        onSuccess: () => toast({ type: "success", title: rule.isActive ? "규칙 중지됨" : "규칙 재개됨" }),
        onError: fail("변경 실패"),
      },
    );

  const handleDelete = (rule: WatchRuleResponse) =>
    remove.mutate(rule.id, {
      onSuccess: () => toast({ type: "success", title: "규칙 삭제됨" }),
      onError: fail("삭제 실패"),
    });

  if (!isLoggedIn) {
    return (
      <div className="mx-auto max-w-3xl p-4 sm:p-6">
        <EmptyState
          icon={Robot}
          title="로그인이 필요합니다"
          description="자동 주문 규칙을 만들려면 로그인해주세요."
        />
      </div>
    );
  }

  const pending = create.isPending || update.isPending || remove.isPending;

  return (
    <div className="animate-fade-up mx-auto max-w-3xl space-y-6 p-4 sm:p-6">
      <header>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">자동 주문 규칙</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-dracula-comment">
          이벤트가 감지되면 모의투자 계좌로 자동 주문합니다 — 화면을 보고 있지 않아도 동작합니다.
        </p>
      </header>

      {/* 경계를 화면에서 분명히 한다 — 실계좌는 자동 주문하지 않는다(ADR-025/036). */}
      <div className="rounded-lg border border-dracula-orange/30 bg-dracula-orange/10 px-4 py-3">
        <p className="text-sm text-gray-800 dark:text-dracula-fg">
          <strong>모의투자 계좌에만 적용됩니다.</strong>{" "}
          <Link href="/brokerage" className="underline">실전투자</Link> 계좌는 자동으로 주문하지 않으며, 실계좌 주문은 항상 직접 확인하고 실행해야 합니다.
        </p>
        <p className="mt-1 text-xs text-gray-600 dark:text-dracula-comment">
          자동 주문도 <Link href="/risk" className="underline">리스크 한도</Link>를 똑같이 통과해야 합니다 — 한도를 넘으면 규칙이 있어도 체결되지 않고 거부 이유가 이력에 남습니다.
        </p>
      </div>

      <Card className="overflow-hidden">
        <div className="border-b border-gray-200 bg-gray-50 px-4 py-3 dark:border-dracula-line dark:bg-transparent">
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">새 규칙</span>
        </div>
        <WatchRuleForm onSubmit={handleCreate} submitting={create.isPending} />
      </Card>

      <div className="flex gap-1 border-b border-gray-200 dark:border-dracula-line">
        {([["rules", `내 규칙${rules?.length ? ` (${rules.length})` : ""}`], ["history", "발동 이력"]] as const).map(
          ([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setTab(key)}
              aria-current={tab === key ? "page" : undefined}
              className={`px-4 py-2 text-sm font-semibold transition-colors ${
                tab === key
                  ? "border-b-2 border-dracula-purple text-gray-900 dark:text-dracula-fg"
                  : "text-gray-500 dark:text-dracula-comment"
              }`}
            >
              {label}
            </button>
          ),
        )}
      </div>

      <Card className="overflow-hidden">
        {tab === "rules" ? (
          rulesLoading ? (
            <div className="h-32 animate-shimmer bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 bg-[length:200%_100%] dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15" />
          ) : !rules?.length ? (
            <EmptyState
              icon={Robot}
              title="아직 규칙이 없습니다"
              description="위에서 종목과 이벤트를 골라 첫 규칙을 만들어보세요."
            />
          ) : (
            <ul className="divide-y divide-gray-100 dark:divide-dracula-line/40">
              {rules.map((rule) => (
                <WatchRuleRow
                  key={rule.id}
                  rule={rule}
                  stockLabel={stockLabel(rule.stockId)}
                  onToggle={handleToggle}
                  onDelete={handleDelete}
                  pending={pending}
                />
              ))}
            </ul>
          )
        ) : executionsLoading ? (
          <div className="h-32 animate-shimmer bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 bg-[length:200%_100%] dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15" />
        ) : (
          <WatchRuleExecutionList executions={executions ?? []} ruleLabels={ruleLabels} />
        )}
      </Card>
    </div>
  );
}
