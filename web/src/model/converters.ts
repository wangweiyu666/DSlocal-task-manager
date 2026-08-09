import type { DraftTask, TaskFields } from "./types";
import type { Dst1Task } from "../protocol/types";
import { createLocalId } from "../protocol/id";

export function fieldsFromDst1(task: Dst1Task): TaskFields {
  let deadlineMode: TaskFields["deadlineMode"] = "default";
  let deadline = "";
  if (task.l === null) deadlineMode = "none";
  else if (typeof task.l === "string") {
    deadlineMode = task.l.includes("T") ? "datetime" : "date";
    deadline = task.l;
  }
  return {
    name: task.n,
    required: task.r === 1,
    description: task.d ?? "",
    taskDate: task.y ?? "",
    deadlineMode,
    deadline,
    points: task.p ?? 0,
    order: task.o ?? null,
    steps: task.s ?? [],
    recurrence: task.x ?? null,
    completionMessage: task.m ?? "",
    reminders: task.h ?? [],
    execution: task.u ?? null
  };
}

export function draftTaskFromDst1(task: Dst1Task, groupId: string | null, source: DraftTask["source"] = "existing"): DraftTask {
  return { ...fieldsFromDst1(task), taskId: task.i, groupId, draftItemId: createLocalId("item"), source };
}
