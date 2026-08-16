import type { DraftRecord, DraftTask, GroupRecord, TaskRecord } from "../model/types";
import { createTransportId } from "./id";
import { compactTask } from "./dst1";
import type { Dst1Batch, Dst1Task } from "./types";

export function draftTaskToDst1(task: DraftTask): Dst1Task {
  const result: Dst1Task = {
    i: task.taskId,
    n: task.name.trim().normalize("NFC"),
    r: task.required ? 1 : 0
  };
  if (task.description !== "") result.d = task.description.trim().normalize("NFC");
  if (!task.recurrence) {
    if (task.taskDate) result.y = task.taskDate;
    if (task.deadlineMode === "date" || task.deadlineMode === "datetime") result.l = task.deadline;
    else if (task.deadlineMode === "none") result.l = null;
  }
  if (task.points !== 0) result.p = task.points;
  if (task.order !== null) result.o = task.order;
  if (task.steps.length) result.s = task.steps.map((step) => ({ n: step.n.trim().normalize("NFC"), r: step.r }));
  if (task.recurrence) result.x = task.recurrence;
  if (task.completionMessage !== "") result.m = task.completionMessage.trim().normalize("NFC");
  if (!task.recurrence && task.reminders.length) result.h = task.reminders;
  if (task.execution) result.u = task.execution;
  return compactTask(result);
}

export function buildBatch(draft: DraftRecord, groups: GroupRecord[]): Dst1Batch {
  const batch: Dst1Batch = { v: 1, b: createTransportId() };
  if (draft.domNameMode === "set") batch.d = draft.domName.trim().normalize("NFC");
  else if (draft.domNameMode === "clear") batch.d = "";
  if (draft.description.trim()) batch.m = draft.description.trim().normalize("NFC");
  const groupMap = new Map(groups.map((group) => [group.id, group]));
  const grouped = new Map<string, DraftTask[]>();
  const ungrouped: DraftTask[] = [];
  for (const task of draft.tasks) {
    if (task.groupId && groupMap.has(task.groupId)) grouped.set(task.groupId, [...(grouped.get(task.groupId) ?? []), task]);
    else ungrouped.push(task);
  }
  const groupIds = new Set([...draft.includeGroupIds, ...grouped.keys()]);
  if (groupIds.size) batch.g = [...groupIds].map((id) => {
    const group = groupMap.get(id)!;
    return {
      i: id,
      n: group.name.trim().normalize("NFC"),
      cm: group.completeMessage.trim().normalize("NFC"),
      im: group.incompleteMessage.trim().normalize("NFC"),
      ...(grouped.get(id)?.length ? { t: grouped.get(id)!.map(draftTaskToDst1) } : {})
    };
  });
  if (ungrouped.length) batch.t = ungrouped.map(draftTaskToDst1);
  if (draft.cancellations.length) batch.z = [...new Set(draft.cancellations)];
  if (draft.exceptions?.length) batch.e = draft.exceptions.map((item) => item.directive);
  return batch;
}

export function taskRecordFromDraft(task: DraftTask, previous?: TaskRecord): TaskRecord {
  const now = new Date().toISOString();
  const { draftItemId: _draftItemId, taskId, source: _source, ...fields } = task;
  const normalizedFields = task.recurrence
    ? { ...fields, taskDate: "", deadlineMode: "default" as const, deadline: "", reminders: [] }
    : fields;
  return {
    ...normalizedFields,
    id: taskId,
    createdAt: previous?.createdAt ?? now,
    updatedAt: now,
    lastGeneratedAt: now,
    version: (previous?.version ?? 0) + 1
  };
}
