import { createLocalId, createTransportId } from "../protocol/id";
import type { DraftRecord, DraftTask, TaskFields, TaskRecord, TemplateRecord } from "./types";

export const emptyTaskFields = (): TaskFields => ({
  name: "",
  required: true,
  description: "",
  taskDate: "",
  deadlineMode: "default",
  deadline: "",
  points: 0,
  order: null,
  steps: [],
  recurrence: null,
  completionMessage: "",
  reminders: [],
  execution: null
});

export function createDraft(name = "未命名批次"): DraftRecord {
  const now = new Date().toISOString();
  return {
    id: createLocalId("draft"),
    name,
    description: "",
    domNameMode: "preserve",
    domName: "",
    includeGroupIds: [],
    tasks: [],
    cancellations: [],
    createdAt: now,
    updatedAt: now
  };
}

export function createDraftTask(fields: Partial<TaskFields> = {}, source: DraftTask["source"] = "new"): DraftTask {
  return {
    ...emptyTaskFields(),
    ...fields,
    draftItemId: createLocalId("item"),
    taskId: createTransportId(),
    groupId: null,
    source
  };
}

export function draftTaskFromTask(task: TaskRecord, source: DraftTask["source"] = "existing"): DraftTask {
  const { id, groupId, createdAt: _createdAt, updatedAt: _updatedAt, lastGeneratedAt: _lastGeneratedAt, version: _version, ...fields } = task;
  return { ...fields, taskId: id, groupId, draftItemId: createLocalId("item"), source };
}

export function draftTaskFromTemplate(template: TemplateRecord): DraftTask {
  const { id: _id, title: _title, groupId, createdAt: _createdAt, updatedAt: _updatedAt, ...fields } = template;
  return { ...fields, taskId: createTransportId(), groupId, draftItemId: createLocalId("item"), source: "template" };
}
