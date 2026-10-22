"use client";

import type { PortfolioAccount } from "@monticker/types";
import { Btn, Notice, Panel } from "@/components/terminal";
import { Skeleton } from "@/components/portfolio/PaperStates";
import { useDeleteAccount, useTransactions } from "@/hooks/usePortfolio";
import { AddTransactionForm } from "./AddTransactionForm";
import { ImportHistory } from "./ImportHistory";
import { ImportPanel } from "./ImportPanel";
import { TransactionsTable } from "./TransactionsTable";
import { ACCOUNT_KIND_LABEL, INSTITUTION_LABEL, SOURCE_LABEL } from "./labels";

const TX_PAGE = 200;

/** 선택한 계좌 — 파일 업로드 계좌는 가져오기·기록, 손 입력 계좌는 거래 추가. 둘 다 거래 목록 */
export function AccountDetail({ account, consentMissing, onDeleted }: { account: PortfolioAccount; consentMissing: boolean; onDeleted: () => void }) {
  const txs = useTransactions(account.id);
  const del = useDeleteAccount();
  const csv = account.source === "CSV";

  const remove = () => {
    if (!confirm(`'${account.name}' 계좌와 거래를 모두 지울까요? 되돌릴 수 없습니다.`)) return;
    del.mutate(account.id, { onSuccess: onDeleted });
  };

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-[10px] bg-tm-panel px-3.5 py-2.5">
        <h2 className="m-0 text-15 font-semibold">{account.name}</h2>
        <span className="text-xs text-tm-muted">
          {INSTITUTION_LABEL[account.institution]} · {ACCOUNT_KIND_LABEL[account.accountKind]} · {SOURCE_LABEL[account.source]}
          {account.accountRefMasked && <span className="num"> · {account.accountRefMasked}</span>}
        </span>
        <Btn kind="danger" size="sm" icon="trash" className="ml-auto" onClick={remove} disabled={del.isPending}>계좌 삭제</Btn>
      </div>
      {del.isError && <Notice tone="danger">{del.error instanceof Error ? del.error.message : "계좌를 지우지 못했습니다."}</Notice>}

      {csv ? (
        <Panel tabs={["파일 가져오기", "가져오기 기록"]} actions={[]} closable={false}
          render={(k) => (k === "파일 가져오기"
            ? (consentMissing
              ? <Notice tone="warn">계좌 정보 수집·이용에 다시 동의해야 가져올 수 있습니다. 화면 위의 안내에서 동의해 주세요.</Notice>
              : <ImportPanel accountId={account.id} />)
            : <ImportHistory accountId={account.id} />)}
        />
      ) : (
        <Panel tabs={["거래 추가"]} actions={[]} closable={false}>
          <AddTransactionForm accountId={account.id} />
        </Panel>
      )}

      <Panel tabs={["거래 내역"]} actions={[]} closable={false} bodyClassName="px-1.5 pb-1.5 pt-1">
        {txs.isPending ? <Skeleton className="h-32" /> : <TransactionsTable accountId={account.id} rows={txs.data ?? []} />}
        {(txs.data?.length ?? 0) >= TX_PAGE && <p className="m-0 px-2 py-1.5 text-2xs text-tm-muted">최근 {TX_PAGE}건까지 보여 줍니다.</p>}
      </Panel>
    </div>
  );
}
