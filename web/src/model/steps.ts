import type { Dst1Execution, Dst1Step } from "../protocol/types";
import { createTransportId, legacyExecutionStepId, legacyStepId } from "../protocol/id";

export function normalizeEditableSteps(taskId: string | undefined, taskName: string, input: Dst1Step[], execution: Dst1Execution | null): { steps: Dst1Step[]; execution: Dst1Execution | null } {
  const steps = input.map((step, index) => ({ ...step, i: step.i ?? (taskId ? legacyStepId(taskId, index) : createTransportId()) }));
  if (steps.length === 0) return { steps, execution: execution?.k === 5 ? null : execution };
  if (execution && execution.k !== 5) {
    const rootId = taskId ? legacyExecutionStepId(taskId) : createTransportId();
    if (!steps.some((step) => step.i === rootId)) steps.push({ i: rootId, n: taskName, r: 1, u: execution });
  }
  return { steps, execution: { k: 5 } };
}
