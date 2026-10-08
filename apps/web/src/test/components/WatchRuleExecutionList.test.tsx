import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import type { WatchRuleExecutionResponse, WatchRuleResponse } from "@monticker/types";
import { WatchRuleExecutionList } from "@/components/watchrule/WatchRuleExecutionList";

const rule: WatchRuleResponse = {
  id: 3,
  stockId: 19,
  eventType: "PRICE_SPIKE",
  side: "BUY",
  quantity: 5,
  minImportanceScore: 75,
  cooldownSec: 300,
  isActive: true,
  createdAt: "2026-09-23T11:00:00Z",
};

const labels = new Map([[3, { stockLabel: "카카오 (035720)", rule }]]);

function execution(over: Partial<WatchRuleExecutionResponse>): WatchRuleExecutionResponse {
  return {
    id: 1, watchRuleId: 3, stockEventId: 100, status: "EXECUTED",
    orderId: null, fillPrice: null, quantity: null, reason: null,
    createdAt: "2026-09-23T11:27:56Z",
    ...over,
  };
}

describe("WatchRuleExecutionList", () => {
  it("발동 이력이 없으면 안내를 보여준다", () => {
    render(<WatchRuleExecutionList executions={[]} ruleLabels={labels} />);
    expect(screen.getByText("아직 발동한 규칙이 없습니다")).toBeInTheDocument();
  });

  it("체결은 수량과 체결가를 보여준다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ status: "EXECUTED", orderId: 2994, quantity: 5, fillPrice: 45462 })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("체결")).toBeInTheDocument();
    expect(screen.getByText(/5주/)).toHaveTextContent("45,462원");
  });

  // 거부·건너뜀은 이유가 핵심이다 — "왜 안 샀지"에 답하지 못하면 이 화면의 의미가 없다.
  it("거부는 이유를 함께 보여준다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ status: "REJECTED", reason: "리스크 한도: ConcentrationRule" })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("거부")).toBeInTheDocument();
    expect(screen.getByText("리스크 한도: ConcentrationRule")).toBeInTheDocument();
  });

  it("건너뜀도 이유를 함께 보여준다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ status: "SKIPPED", reason: "쿨다운 300초 이내 재발동" })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("건너뜀")).toBeInTheDocument();
    expect(screen.getByText("쿨다운 300초 이내 재발동")).toBeInTheDocument();
  });

  // 규칙을 지워도 이력은 남는다(ON DELETE CASCADE 는 DB 쪽이지만, 목록이 먼저 도착하는 순간이 있다).
  it("규칙 정보가 없어도 이력이 깨지지 않는다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ watchRuleId: 999, status: "SKIPPED", reason: "중요도 미달" })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("규칙 #999")).toBeInTheDocument();
  });

  // ADR-095 — 지정가 접수(미체결)와 그룹 규칙의 발동 종목
  it("지정가 접수는 지정가와 미체결을, 그룹 규칙은 발동 종목을 보여준다", () => {
    const groupRule: WatchRuleResponse = { ...rule, id: 8, stockId: null, targetType: "GROUP", targetGroupId: 4, targetGroupName: "반도체" };
    render(
      <WatchRuleExecutionList
        executions={[execution({ watchRuleId: 8, status: "PLACED", orderId: 77, quantity: 3, limitPrice: 69650, stockId: 19, reason: "지정가 69650 접수 — 미체결" })]}
        ruleLabels={new Map([[8, { stockLabel: "그룹 · 반도체", rule: groupRule }]])}
        stockLabel={(id) => (id === 19 ? "카카오 (035720)" : `종목 #${id}`)}
      />,
    );
    expect(screen.getByText("지정가 접수")).toBeInTheDocument();
    expect(screen.getByText(/3주 지정가 69,650원 · 미체결/)).toBeInTheDocument();
    expect(screen.getByText("→ 카카오 (035720)")).toBeInTheDocument();
  });

  // ADR-098 — 접수했던 지정가가 나중에 체결·취소되면 그 결과를 보여준다.
  it("나중에 체결된 지정가는 지정가와 체결가를 보여준다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ status: "FILLED", orderId: 77, quantity: 3, limitPrice: 69650, fillPrice: 69500,
          resolvedAt: "2026-09-23T11:40:00Z", reason: "지정가 69650 접수 — 미체결" })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("지정가 체결")).toBeInTheDocument();
    expect(screen.getByText(/3주 지정가 69,650원 → 체결 69,500원/)).toBeInTheDocument();
    // 접수 당시의 "미체결" 문구는 더 이상 보이지 않는다
    expect(screen.queryByText("지정가 69650 접수 — 미체결")).not.toBeInTheDocument();
  });

  it("체결 전에 취소된 지정가는 취소 사유를 보여준다", () => {
    render(
      <WatchRuleExecutionList
        executions={[execution({ status: "CANCELLED", orderId: 78, quantity: 3, limitPrice: 69650,
          resolvedAt: "2026-09-23T11:40:00Z", reason: "지정가 미체결 취소 — 사용자 취소" })]}
        ruleLabels={labels}
      />,
    );
    expect(screen.getByText("지정가 취소")).toBeInTheDocument();
    expect(screen.getByText(/3주 지정가 69,650원 · 미체결 취소/)).toBeInTheDocument();
    expect(screen.getByText("지정가 미체결 취소 — 사용자 취소")).toBeInTheDocument();
  });
});
