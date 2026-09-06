import type { DraftTask, TaskFields } from "./types";
import type { Dst1Task } from "../protocol/types";
import { createLocalId } from "../protocol/id";
import { normalizeEditableSteps } from "./steps";

export function fieldsFromDst1(task: Dst1Task): TaskFields {
  let deadlineMode: TaskFields["deadlineMode"] = "default";
  let deadline = "";
  if (task.l === null) deadlineMode = "none";
  else if (typeof task.l === "string") {
    deadlineMode = task.l.includes("T") ? "datetime" : "date";
    deadline = task.l;
  }
  const steps = task.s ?? [];
  const execution = task.u ?? null;
  return {
    name: task.n,
    required: task.r === 1,
    description: task.d ?? "",
    taskDate: task.y ?? "",
    taskDateIntent: task.y ? "set" : "preserve",
    deadlineMode,
    deadline,
    points: task.p ?? 0,
    order: task.o ?? null,
    steps,
    recurrence: task.x ?? null,
    completionMessage: task.m ?? "",
    reminders: task.h ?? [],
    execution
  };
}

export function draftTaskFromDst1(task: Dst1Task, groupId: string | null, source: DraftTask["source"] = "existing"): DraftTask {
  const fields = fieldsFromDst1(task);
  const normalized = normalizeEditableSteps(task.i, task.n, fields.steps, fields.execution);
  return { ...fields, ...normalized, taskId: task.i, groupId, draftItemId: createLocalId("item"), source };
}
