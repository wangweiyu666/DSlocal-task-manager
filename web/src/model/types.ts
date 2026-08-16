import type { Dst11Exception, Dst1Batch, Dst1Execution, Dst1Recurrence, Dst1Step, Dst1Task } from "../protocol/types";

export interface GroupRecord {
  id: string;
  name: string;
  completeMessage: string;
  incompleteMessage: string;
  order: number;
  createdAt: string;
  updatedAt: string;
}

export interface TaskFields {
  name: string;
  required: boolean;
  description: string;
  taskDate: string;
  deadlineMode: "default" | "date" | "datetime" | "none";
  deadline: string;
  points: number;
  order: number | null;
  steps: Dst1Step[];
  recurrence: Dst1Recurrence | null;
  completionMessage: string;
  reminders: number[];
  execution: Dst1Execution | null;
}

export interface TaskRecord extends TaskFields {
  id: string;
  groupId: string | null;
  createdAt: string;
  updatedAt: string;
  lastGeneratedAt: string | null;
  version: number;
}

export interface TaskRevision {
  id: string;
  taskId: string;
  version: number;
  generatedAt: string;
  batchId: string;
  snapshot: Dst1Task;
}

export interface TaskExceptionRecord {
  id: string;
  taskId: string;
  date: string;
  directive: Dst11Exception;
  createdAt: string;
  updatedAt: string;
}

export interface DraftException {
  draftItemId: string;
  directive: Dst11Exception;
}

export interface TaskExceptionRevision {
  id: string;
  exceptionId: string;
  taskId: string;
  date: string;
  generatedAt: string;
  batchId: string;
  snapshot: Dst11Exception;
}

export interface TemplateRecord extends TaskFields {
  id: string;
  title: string;
  groupId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DraftTask extends TaskFields {
  draftItemId: string;
  taskId: string;
  groupId: string | null;
  source: "new" | "existing" | "template" | "delay";
}

export interface DraftRecord {
  id: string;
  name: string;
  description: string;
  domNameMode: "preserve" | "set" | "clear";
  domName: string;
  includeGroupIds: string[];
  tasks: DraftTask[];
  cancellations: string[];
  exceptions: DraftException[];
  createdAt: string;
  updatedAt: string;
}

export interface BatchHistoryRecord {
  id: string;
  draftName: string;
  generatedAt: string;
  envelope: string;
  jsonBytes: number;
  envelopeChars: number;
  taskCount: number;
  snapshot: Dst1Batch;
}

export interface AppSettings {
  id: "app";
  domName: string;
  theme: "system" | "light" | "dark";
  timeZone: string;
  lastDraftId: string | null;
  updatedAt: string;
}

export interface DomBackup {
  format: "DSDOM";
  version: 1;
  minorVersion?: 2;
  createdAt: string;
  groups: GroupRecord[];
  tasks: TaskRecord[];
  templates: TemplateRecord[];
  drafts: DraftRecord[];
  batchHistory: BatchHistoryRecord[];
  taskRevisions: TaskRevision[];
  taskExceptions: TaskExceptionRecord[];
  exceptionRevisions: TaskExceptionRevision[];
  settings: AppSettings;
}

export type BackupTable = "groups" | "tasks" | "templates" | "drafts" | "batchHistory" | "taskRevisions" | "taskExceptions" | "exceptionRevisions";

export interface BackupConflict {
  key: string;
  table: BackupTable | "settings";
  id: string;
  label: string;
}
