import type { GroupRecord, DraftRecord, DraftTask, TaskExceptionRevision, TaskRevision } from "./types";
import { buildBatch, taskRecordFromDraft } from "../protocol/builder";
import { encodeDst1, type EncodedDst1 } from "../protocol/dst1";
import type { Dst1Batch, Dst1Task } from "../protocol/types";
import { db } from "../db/database";
import { createLocalId } from "../protocol/id";
import { fieldsFromDst1 } from "./converters";

export interface PreviewSnapshot { batch: Dst1Batch; encoded: EncodedDst1; draftSnapshot: DraftRecord }
export function naturalDateInZone(timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const values = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
  if (!values.year || !values.month || !values.day) throw new Error("无法计算任务日期");
  return `${values.year}-${values.month}-${values.day}`;
}
export function createPreviewSnapshot(draft: DraftRecord, groups: GroupRecord[], timeZone: string): PreviewSnapshot {
  const draftSnapshot = structuredClone(draft) as DraftRecord;
  const date = naturalDateInZone(timeZone);
  const initial = buildBatch(draftSnapshot, groups);
  const taskMap = new Map(draftSnapshot.tasks.map((task) => [task.taskId, task]));
  const normalize = (task: Dst1Task): Dst1Task => {
    const source = taskMap.get(task.i);
    if (!source || source.taskDateIntent === "preserve" || (source.source === "existing" && source.taskDateIntent === undefined)) return task;
    if (source.recurrence) return task.x && !task.x.s ? { ...task, x: { ...task.x, s: date } } : task;
    return task.y ? task : { ...task, y: date };
  };
  const batch = { ...initial, t: initial.t?.map(normalize), g: initial.g?.map((group) => ({ ...group, t: group.t?.map(normalize) })) };
  const all = [...(batch.t ?? []), ...(batch.g ?? []).flatMap((group) => group.t ?? [])];
  for (const task of draftSnapshot.tasks) { const normalized = all.find((item) => item.i === task.taskId); if (normalized?.y) task.taskDate = normalized.y; if (normalized?.x?.s && task.recurrence) task.recurrence = { ...task.recurrence, s: normalized.x.s }; }
  return { batch, encoded: encodeDst1(batch), draftSnapshot };
}
export async function commitPreviewSnapshot(preview: PreviewSnapshot, draftName = preview.draftSnapshot.name, now = new Date().toISOString()): Promise<DraftTask[]> {
  const snapshot = preview.draftSnapshot;
  const nextTasks: DraftTask[] = [];
  await db.transaction("rw", [db.batchHistory, db.tasks, db.taskRevisions, db.taskExceptions, db.exceptionRevisions, db.drafts], async () => {
    await db.batchHistory.add({ id: preview.batch.b, draftName, generatedAt: now, envelope: preview.encoded.envelope, jsonBytes: preview.encoded.jsonBytes, envelopeChars: preview.encoded.envelope.length, taskCount: snapshot.tasks.length, snapshot: preview.batch });
    const all = [...(preview.batch.t ?? []), ...(preview.batch.g ?? []).flatMap((group) => group.t ?? [])];
    for (const item of snapshot.tasks) { const snap = all.find((task) => task.i === item.taskId); if (!snap) throw new Error(`预览快照缺少任务 ${item.taskId}`); const normalized = fieldsFromDst1(snap); const normalizedItem = { ...item, ...normalized }; const previous = await db.tasks.get(item.taskId); const record = taskRecordFromDraft(normalizedItem, previous); await db.tasks.put(record); const revision: TaskRevision = { id: createLocalId("revision"), taskId: record.id, version: record.version, generatedAt: now, batchId: preview.batch.b, snapshot: snap }; await db.taskRevisions.add(revision); nextTasks.push({ ...normalizedItem, source: "existing" }); }
    for (const item of snapshot.exceptions ?? []) { const directive = preview.batch.e?.find((entry) => entry.i === item.directive.i && entry.y === item.directive.y) ?? item.directive; const exceptionId = `${directive.i}|${directive.y}`; const existing = await db.taskExceptions.get(exceptionId); const clear = Object.keys(directive).every((key) => key === "i" || key === "y"); if (clear) await db.taskExceptions.delete(exceptionId); else await db.taskExceptions.put({ id: exceptionId, taskId: directive.i, date: directive.y, directive, createdAt: existing?.createdAt ?? now, updatedAt: now }); const revision: TaskExceptionRevision = { id: createLocalId("exception-revision"), exceptionId, taskId: directive.i, date: directive.y, generatedAt: now, batchId: preview.batch.b, snapshot: directive }; await db.exceptionRevisions.add(revision); }
    await db.drafts.update(snapshot.id, { tasks: nextTasks, updatedAt: now });
  });
  return nextTasks;
}
