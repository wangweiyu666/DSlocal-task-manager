import { z } from "zod";
import type { BackupConflict, BackupTable, DomBackup } from "../model/types";
import { db, getSettings } from "./database";
import { decodeDst1 } from "../protocol/dst1";
import { validateDst1Batch } from "../protocol/validation";

const id = z.string().min(1);
const timestamp = z.string().datetime();
const step = z.object({ n: z.string().min(1).max(100), r: z.union([z.literal(0), z.literal(1)]) }).strict();
const recurrence = z.object({
  f: z.union([z.literal(1), z.literal(2)]), s: z.string().optional(), e: z.string().optional(), c: z.number().int().positive().optional(),
  w: z.array(z.number().int().min(1).max(7)).optional(), t: z.string().nullable().optional()
}).strict().refine((value) => !(value.e && value.c), "重复规则不能同时设置结束日期和次数");
const execution = z.discriminatedUnion("k", [
  z.object({ k: z.literal(1), a: z.union([z.literal(1), z.literal(2)]), v: z.number().int().min(1).max(999) }).strict(),
  z.object({ k: z.literal(2), v: z.number().int().min(1).max(3600) }).strict(),
  z.object({ k: z.literal(3) }).strict()
]);
const taskFieldsShape = {
  name: z.string().max(100), required: z.boolean(), description: z.string().max(2000), taskDate: z.string(),
  deadlineMode: z.enum(["default", "date", "datetime", "none"]), deadline: z.string(), points: z.number().int().min(0).max(9999),
  order: z.number().int().nullable(), steps: z.array(step).max(50), recurrence: recurrence.nullable(), completionMessage: z.string().max(500),
  reminders: z.array(z.number().int().min(0).max(10080)).max(5), execution: execution.nullable()
} as const;
const groupRecord = z.object({ id, name: z.string().min(1).max(50), completeMessage: z.string().max(500), incompleteMessage: z.string().max(500), order: z.number().int(), createdAt: timestamp, updatedAt: timestamp }).strict();
const taskRecord = z.object({ ...taskFieldsShape, id, groupId: id.nullable(), createdAt: timestamp, updatedAt: timestamp, lastGeneratedAt: timestamp.nullable(), version: z.number().int().nonnegative() }).strict();
const templateRecord = z.object({ ...taskFieldsShape, id, title: z.string().min(1).max(100), groupId: id.nullable(), createdAt: timestamp, updatedAt: timestamp }).strict();
const draftTask = z.object({ ...taskFieldsShape, draftItemId: id, taskId: id, groupId: id.nullable(), source: z.enum(["new", "existing", "template", "delay"]) }).strict();
const draftException = z.object({ draftItemId: id, directive: z.unknown() }).strict();
const draftRecord = z.object({ id, name: z.string().max(100), description: z.string().max(500), domNameMode: z.enum(["preserve", "set", "clear"]), domName: z.string().max(50), includeGroupIds: z.array(id), tasks: z.array(draftTask), cancellations: z.array(id).max(100), exceptions: z.array(draftException).default([]), createdAt: timestamp, updatedAt: timestamp }).strict();
const batchHistoryRecord = z.object({ id, draftName: z.string(), generatedAt: timestamp, envelope: z.string(), jsonBytes: z.number().int().nonnegative(), envelopeChars: z.number().int().nonnegative(), taskCount: z.number().int().nonnegative(), snapshot: z.unknown() }).strict();
const taskRevision = z.object({ id, taskId: id, version: z.number().int().positive(), generatedAt: timestamp, batchId: id, snapshot: z.unknown() }).strict();
const settingsRecord = z.object({ id: z.literal("app"), domName: z.string().max(50), theme: z.enum(["system", "light", "dark"]), timeZone: z.string().min(1), lastDraftId: id.nullable(), updatedAt: timestamp }).strict();
const taskExceptionRecord = z.object({ id, taskId: id, date: z.string(), directive: z.unknown(), createdAt: timestamp, updatedAt: timestamp }).strict();
const exceptionRevision = z.object({ id, exceptionId: id, taskId: id, date: z.string(), generatedAt: timestamp, batchId: id, snapshot: z.unknown() }).strict();

const backupSchema = z.object({
  format: z.literal("DSDOM"),
  version: z.literal(1),
  minorVersion: z.literal(2).optional(),
  createdAt: z.string().datetime(),
  groups: z.array(groupRecord), tasks: z.array(taskRecord), templates: z.array(templateRecord), drafts: z.array(draftRecord),
  batchHistory: z.array(batchHistoryRecord), taskRevisions: z.array(taskRevision),
  taskExceptions: z.array(taskExceptionRecord).default([]), exceptionRevisions: z.array(exceptionRevision).default([]), settings: settingsRecord
}).strict();

export async function createBackup(): Promise<DomBackup> {
  const [groups, tasks, templates, drafts, batchHistory, taskRevisions, taskExceptions, exceptionRevisions, settings] = await Promise.all([
    db.groups.toArray(), db.tasks.toArray(), db.templates.toArray(), db.drafts.toArray(),
    db.batchHistory.toArray(), db.taskRevisions.toArray(), db.taskExceptions.toArray(), db.exceptionRevisions.toArray(), getSettings()
  ]);
  return { format: "DSDOM", version: 1, minorVersion: 2, createdAt: new Date().toISOString(), groups, tasks, templates, drafts, batchHistory, taskRevisions, taskExceptions, exceptionRevisions, settings };
}

export function parseBackup(json: string): DomBackup {
  let raw: unknown;
  try { raw = JSON.parse(json); }
  catch (error) { throw new Error("配置文件不是有效的 JSON", { cause: error }); }
  const parsed = backupSchema.safeParse(raw);
  if (!parsed.success) throw new Error(`配置文件结构无效：${parsed.error.issues[0]?.path.join(".") || "$"} ${parsed.error.issues[0]?.message ?? "未知错误"}`);
  const backup = parsed.data as DomBackup;
  for (const key of ["groups", "tasks", "templates", "drafts", "batchHistory", "taskRevisions", "taskExceptions", "exceptionRevisions"] as const) {
    const ids = backup[key].map((record) => record.id);
    if (new Set(ids).size !== ids.length) throw new Error(`配置文件结构无效：${key} 中存在重复 ID`);
  }
  const groupIds = new Set(backup.groups.map((group) => group.id));
  if ([...backup.tasks, ...backup.templates].some((record) => record.groupId !== null && !groupIds.has(record.groupId))) throw new Error("配置文件结构无效：任务或模板引用了不存在的积分组");
  for (const draft of backup.drafts) {
    if (draft.tasks.some((task) => task.groupId !== null && !groupIds.has(task.groupId)) || draft.includeGroupIds.some((groupId) => !groupIds.has(groupId))) throw new Error(`配置文件结构无效：草稿“${draft.name}”引用了不存在的积分组`);
    for (const item of draft.exceptions) validateDst1Batch({ v: 1, sv: 1, b: "BackupCheck00004", e: [item.directive] });
  }
  for (const history of backup.batchHistory) {
    validateDst1Batch(history.snapshot);
    const decoded = decodeDst1(history.envelope).batch;
    if (decoded.b !== history.id || history.snapshot.b !== history.id || JSON.stringify(decoded) !== JSON.stringify(history.snapshot)) throw new Error(`配置文件结构无效：历史批次 ${history.id} 的字符串与快照不一致`);
  }
  for (const revision of backup.taskRevisions) validateDst1Batch({ v: 1, b: "BackupCheck00001", t: [revision.snapshot] });
  const taskIds = new Set(backup.tasks.filter((task) => task.recurrence !== null).map((task) => task.id));
  for (const exception of backup.taskExceptions) {
    if (!taskIds.has(exception.taskId) || exception.directive.i !== exception.taskId || exception.directive.y !== exception.date) throw new Error(`配置文件结构无效：单日例外 ${exception.id} 无法关联重复模板`);
    validateDst1Batch({ v: 1, sv: 1, b: "BackupCheck00002", e: [exception.directive] });
  }
  for (const revision of backup.exceptionRevisions) validateDst1Batch({ v: 1, sv: 1, b: "BackupCheck00003", e: [revision.snapshot] });
  return backup;
}

const labels: Record<BackupTable, string> = {
  groups: "积分组",
  tasks: "任务",
  templates: "模板",
  drafts: "草稿",
  batchHistory: "历史批次",
  taskRevisions: "任务版本",
  taskExceptions: "单日例外",
  exceptionRevisions: "例外版本"
};

export async function analyzeBackupConflicts(backup: DomBackup): Promise<BackupConflict[]> {
  const tables: BackupTable[] = ["groups", "tasks", "templates", "drafts", "batchHistory", "taskRevisions", "taskExceptions", "exceptionRevisions"];
  const conflicts: BackupConflict[] = [];
  for (const table of tables) {
    const incoming = backup[table] as Array<{ id: string; name?: string; title?: string; draftName?: string }>;
    const ids = incoming.map((record) => record.id);
    const existing = await db.table(table).where("id").anyOf(ids).primaryKeys();
    for (const id of existing) {
      const record = incoming.find((item) => item.id === id)!;
      conflicts.push({ key: `${table}:${id}`, table, id: String(id), label: `${labels[table]} · ${record.name ?? record.title ?? record.draftName ?? id}` });
    }
  }
  if (await db.settings.get("app")) conflicts.push({ key: "settings:app", table: "settings", id: "app", label: "应用设置 · Dom 名称、主题与时区" });
  return conflicts;
}

export async function importBackup(
  backup: DomBackup,
  mode: "replace" | "merge",
  useImportedConflictKeys: Set<string> = new Set()
): Promise<void> {
  await db.transaction("rw", [db.groups, db.tasks, db.templates, db.drafts, db.batchHistory, db.taskRevisions, db.taskExceptions, db.exceptionRevisions, db.settings], async () => {
    if (mode === "replace") {
      await Promise.all([db.groups.clear(), db.tasks.clear(), db.templates.clear(), db.drafts.clear(), db.batchHistory.clear(), db.taskRevisions.clear(), db.taskExceptions.clear(), db.exceptionRevisions.clear(), db.settings.clear()]);
    }
    const putSelected = async (table: BackupTable): Promise<void> => {
      const target = db.table(table);
      for (const record of backup[table]) {
        const exists = mode === "merge" && await target.get(record.id);
        if (!exists || useImportedConflictKeys.has(`${table}:${record.id}`)) await target.put(record);
      }
    };
    await putSelected("groups");
    await putSelected("tasks");
    await putSelected("templates");
    await putSelected("drafts");
    await putSelected("batchHistory");
    await putSelected("taskRevisions");
    await putSelected("taskExceptions");
    await putSelected("exceptionRevisions");
    if (mode === "replace" || useImportedConflictKeys.has("settings:app")) await db.settings.put(backup.settings);
  });
}

export function downloadBackup(backup: DomBackup): void {
  const date = backup.createdAt.slice(0, 10);
  const blob = new Blob([JSON.stringify(backup, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `dstationery-dom-${date}.dsdom.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}
