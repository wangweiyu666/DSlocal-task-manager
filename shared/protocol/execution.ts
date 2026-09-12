import { Dst1ProtocolError, type Dst1Execution, type Dst1Step } from "./types";

const fail = (path: string, message: string): never => { throw new Dst1ProtocolError("INVALID_VALUE", path, message); };

export function validateExecutionConfiguration(execution: Dst1Execution | null | undefined, path = "u"): void {
  if (execution?.k === 6) {
    if (!execution.t.trim() || Array.from(execution.t).length > 2000 || execution.t !== execution.t.normalize("NFC")) fail(path, "通知正文须非空、使用 NFC，最多 2000 个字符");
  }
  if (execution?.k === 7) {
    if (execution.o.length < 2 || execution.o.length > 50) fail(path, "单选需要 2～50 个选项");
    const ids = new Set<string>();
    for (const option of execution.o) {
      if (!/^[A-Za-z0-9_-]{16}$/u.test(option.i) || ids.has(option.i)) fail(path, "选项 ID 必须合法且唯一");
      ids.add(option.i);
      if (!option.n.trim() || Array.from(option.n).length > 100 || option.n !== option.n.normalize("NFC")) fail(path, "选项名称须非空、使用 NFC，最多 100 个字符");
      if (!Number.isInteger(option.p) || option.p < 0 || option.p > 9999) fail(path, "选项积分必须是 0～9999 的整数");
    }
  }
}

export function validateConditionalSteps(steps: Dst1Step[], path = "s"): void {
  const previous = new Map<string, Dst1Step>();
  steps.forEach((step, index) => {
    validateExecutionConfiguration(step.u, `${path}[${index}].u`);
    if (step.c) {
      const source = previous.get(step.c.s);
      if (source?.u?.k !== 7 || !source.u.o.some((option) => option.i === step.c!.o)) fail(`${path}[${index}].c`, "条件必须引用同一任务中前面的单选步骤及其有效选项");
    }
    if (step.i) previous.set(step.i, step);
  });
}

export type BranchStatus = "APPLICABLE" | "WAITING" | "NOT_APPLICABLE";
export interface BranchAnswer { status: string; selectedOptionId?: string | null; }

/** The order restriction on definitions makes this evaluation acyclic. */
export function evaluateBranches(steps: Dst1Step[], answers: ReadonlyMap<string, BranchAnswer>): BranchStatus[] {
  const branches = new Map<string, BranchStatus>();
  return steps.map((step) => {
    let state: BranchStatus = "APPLICABLE";
    if (step.c) {
      const parent = branches.get(step.c.s);
      const answer = answers.get(step.c.s);
      state = parent === "NOT_APPLICABLE" || answer?.status === "SKIPPED" || answer?.status === "NOT_APPLICABLE"
        ? "NOT_APPLICABLE" : parent !== "APPLICABLE" || answer?.status !== "CONFIRMED"
          ? "WAITING" : answer.selectedOptionId === step.c.o ? "APPLICABLE" : "NOT_APPLICABLE";
    }
    if (step.i) branches.set(step.i, state);
    return state;
  });
}
