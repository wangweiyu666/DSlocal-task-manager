import Dexie, { type EntityTable } from "dexie";
import type {
  AppSettings,
  BatchHistoryRecord,
  DraftRecord,
  GroupRecord,
  TaskRecord,
  TaskExceptionRecord,
  TaskExceptionRevision,
  TaskRevision,
  TemplateRecord
} from "../model/types";

export class DomDatabase extends Dexie {
  groups!: EntityTable<GroupRecord, "id">;
  tasks!: EntityTable<TaskRecord, "id">;
  templates!: EntityTable<TemplateRecord, "id">;
  drafts!: EntityTable<DraftRecord, "id">;
  batchHistory!: EntityTable<BatchHistoryRecord, "id">;
  taskRevisions!: EntityTable<TaskRevision, "id">;
  taskExceptions!: EntityTable<TaskExceptionRecord, "id">;
  exceptionRevisions!: EntityTable<TaskExceptionRevision, "id">;
  settings!: EntityTable<AppSettings, "id">;

  constructor() {
    super("dstationery-dom");
    this.version(1).stores({
      groups: "id, &name, order, updatedAt",
      tasks: "id, groupId, name, updatedAt, lastGeneratedAt",
      templates: "id, groupId, title, updatedAt",
      drafts: "id, name, updatedAt",
      batchHistory: "id, generatedAt, draftName",
      taskRevisions: "id, taskId, [taskId+version], generatedAt, batchId",
      settings: "id"
    });
    this.version(2).stores({
      groups: "id, &name, order, updatedAt",
      tasks: "id, groupId, name, updatedAt, lastGeneratedAt",
      templates: "id, groupId, title, updatedAt",
      drafts: "id, name, updatedAt",
      batchHistory: "id, generatedAt, draftName",
      taskRevisions: "id, taskId, [taskId+version], generatedAt, batchId",
      taskExceptions: "id, &[taskId+date], taskId, date, updatedAt",
      exceptionRevisions: "id, exceptionId, [taskId+date], generatedAt, batchId",
      settings: "id"
    }).upgrade(async (transaction) => {
      await transaction.table("drafts").toCollection().modify((draft: DraftRecord) => {
        draft.exceptions ??= [];
      });
    });
  }
}

export const db = new DomDatabase();

export async function getSettings(): Promise<AppSettings> {
  const existing = await db.settings.get("app");
  if (existing) return existing;
  const now = new Date().toISOString();
  const settings: AppSettings = {
    id: "app",
    domName: "",
    theme: "system",
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Hong_Kong",
    lastDraftId: null,
    updatedAt: now
  };
  await db.settings.put(settings);
  return settings;
}

export async function updateSettings(patch: Partial<Omit<AppSettings, "id">>): Promise<AppSettings> {
  const current = await getSettings();
  const next: AppSettings = { ...current, ...patch, id: "app", updatedAt: new Date().toISOString() };
  await db.settings.put(next);
  return next;
}
