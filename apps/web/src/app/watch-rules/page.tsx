"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import type { WatchRuleResponse } from "@monticker/types";
import { Notice, Panel, PanelRow, TerminalPage, dirClass, fmtSigned, type TopStat } from "@/components/terminal";
import { EmptyNote, LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import { fmtDateTime } from "@/components/portfolio/format";
import { WatchRuleExecutionList } from "@/components/watchrule/WatchRuleExecutionList";
import { WatchRuleForm, type WatchRuleFormValue } from "@/components/watchrule/WatchRuleForm";
import { RULE_PNL_HINT, WatchRuleRow, targetLabel } from "@/components/watchrule/WatchRuleRow";
import { usePaperPnlByOrigin } from "@/hooks/usePaperTrade";
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
  // ADR-085 — 규칙 경유 체결의 실현 손익(서버가 체결에 남긴 출처로 집계)
  const { data: rulePnl } = usePaperPnlByOrigin("WATCH_RULE", isLoggedIn);
  const pnlByRule = useMemo(() => {
    const m = new Map<number, number>();
    rulePnl?.byRef.forEach((r) => { if (r.originRef != null && r.sellCount > 0) m.set(r.originRef, r.realizedPnl); });
    return m;
  }, [rulePnl]);
  const create = useCreateWatchRule();
  const update = useUpdateWatchRule();
  const remove = useDeleteWatchRule();
  const { toast } = useToast();

  // 룰은 stockId 만 들고 있다 — 표시용 종목명을 한 번에 채운다. 그룹 규칙(ADR-095)은 발동 기록의 종목을 채운다.
  useEffect(() => {
    if (!rules?.length) return;
    const ids = [
      ...rules.map((r) => r.stockId),
      ...(executions ?? []).map((e) => e.stockId),
    ].filter((id): id is number => id != null);
    const missing = [...new Set(ids)].filter((id) => !stocks.has(id));
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
  }, [rules, executions, stocks]);

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
    rules?.forEach((r) => map.set(r.id, { stockLabel: targetLabel(r, stockLabel), rule: r }));
    return map;
  }, [rules, stockLabel]);

  const fail = (title: string) => (e: unknown) =>
    toast({ type: "error", title, message: e instanceof ApiError ? e.message : "잠시 후 다시 시도해주세요." });

  const handleCreate = (value: WatchRuleFormValue) =>
    create.mutate(value, {
      onSuccess: () => toast({ type: "success", title: "규칙 생성됨", message: "이벤트가 감지되면 모의투자 계좌로 자동 주문합니다." }),
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

  // 규칙별 마지막 발동·오늘 발동 횟수 — 발동 이력(최근 50건)에서 센다
  const fired = useMemo(() => {
    const today = new Date().toDateString();
    const m = new Map<number, { last: string; today: number }>();
    (executions ?? []).forEach((e) => {
      const cur = m.get(e.watchRuleId) ?? { last: e.createdAt, today: 0 };
      if (e.createdAt > cur.last) cur.last = e.createdAt;
      if (new Date(e.createdAt).toDateString() === today) cur.today += 1;
      m.set(e.watchRuleId, cur);
    });
    return m;
  }, [executions]);

  const title = { title: "자동 주문 규칙 (Watch Rule)", crumb: "모의투자" };

  if (!isLoggedIn) {
    return (
      <TerminalPage {...title}>
        <LoginRequired message={<><b className="block text-dracula-fg">로그인이 필요합니다</b>자동 주문 규칙을 만들려면 로그인해주세요.</>} icon="zap" />
      </TerminalPage>
    );
  }

  const pending = create.isPending || update.isPending || remove.isPending;
  const today = new Date().toDateString();
  const todayExec = (executions ?? []).filter((e) => new Date(e.createdAt).toDateString() === today);
  const stats: TopStat[] = [
    { label: "활성 규칙", value: rules ? `${rules.filter((r) => r.isActive).length} / ${rules.length}` : "—" },
    { label: "오늘 발동", value: `${todayExec.length}회`, tone: "text-dracula-purple" },
    { label: "차단됨", value: `${todayExec.filter((e) => e.status === "REJECTED").length}회` },
    {
      label: "규칙 경유 손익",
      value: !rulePnl || rulePnl.sellCount === 0 ? "—" : fmtSigned(rulePnl.totalRealizedPnl),
      tone: !rulePnl || rulePnl.sellCount === 0 ? "text-tm-muted" : dirClass(rulePnl.totalRealizedPnl),
      hint: RULE_PNL_HINT,
    },
  ];

  return (
    <TerminalPage {...title} stats={stats}>
      {/* 경계를 화면에서 분명히 한다 — 실계좌는 자동 주문하지 않는다(ADR-025/036). */}
      <Notice tone="info">
        Watch Rule은 <b className="text-dracula-fg">모의투자 계좌에만</b> 적용됩니다. <Link href="/brokerage" className="underline">실전투자</Link> 계좌는 자동으로
        주문하지 않으며, 실계좌 주문은 항상 직접 확인하고 실행해야 합니다. 자동 주문도{" "}
        <Link href="/risk" className="underline">리스크 한도</Link>를 똑같이 통과해야 합니다 — 한도를 넘으면 규칙이 있어도 체결되지 않고 거부 이유가 이력에 남습니다.
      </Notice>

      <PanelRow>
        <Panel
          tabs={[
            { key: "rules", label: `내 규칙${rules?.length ? ` ${rules.length}` : ""}` },
            { key: "history", label: "발동 기록" },
          ]}
          active={tab}
          onTabChange={(k) => setTab(k as "rules" | "history")}
          actions={[]}
          className="flex-[999_1_620px]"
        >
          {tab === "rules" ? (
            rulesLoading ? (
              <Skeleton className="h-32" />
            ) : !rules?.length ? (
              <EmptyNote>
                <b className="block text-dracula-fg">아직 규칙이 없습니다</b>
                오른쪽에서 종목과 이벤트를 골라 첫 규칙을 만들어보세요.
              </EmptyNote>
            ) : (
              <ul className="m-0 flex list-none flex-col gap-2 p-0">
                {rules.map((rule) => {
                  const f = fired.get(rule.id);
                  return (
                    <WatchRuleRow
                      key={rule.id}
                      rule={rule}
                      stockLabel={targetLabel(rule, stockLabel)}
                      onToggle={handleToggle}
                      onDelete={handleDelete}
                      pending={pending}
                      lastFired={f ? fmtDateTime(f.last) : null}
                      todayCount={f?.today ?? 0}
                      realizedPnl={pnlByRule.get(rule.id) ?? null}
                    />
                  );
                })}
              </ul>
            )
          ) : executionsLoading ? (
            <Skeleton className="h-32" />
          ) : (
            <WatchRuleExecutionList executions={executions ?? []} ruleLabels={ruleLabels} stockLabel={stockLabel} />
          )}
        </Panel>

        <Panel tabs={["새 규칙"]} actions={[]} className="flex-[1_1_340px]">
          <WatchRuleForm onSubmit={handleCreate} submitting={create.isPending} />
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
