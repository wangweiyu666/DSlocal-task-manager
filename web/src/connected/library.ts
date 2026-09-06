import type { EditableTask } from "../components/TaskEditor";
import { fieldsFromDst1 } from "../model/converters";
import type { GroupRecord } from "../model/types";
import { draftTaskToDst1 } from "../protocol/builder";
import { createLocalId, createTransportId } from "../protocol/id";
import type { Dst1Batch, Dst1Group, Dst1Task } from "../protocol/types";

export interface CloudTaskDefinition {
  task: Dst1Task;
  group: Dst1Group | null;
}

export function unpackCloudTask(content: Record<string, unknown> | undefined): CloudTaskDefinition | null {
  if (!content) return null;
  const direct = (content.t as Dst1Task[] | undefined) ?? [];
  const groups = (content.g as Dst1Group[] | undefined) ?? [];
  const grouped = groups.flatMap((group) => (group.t ?? []).map((task) => ({ task, group })));
  const definitions = [...direct.map((task) => ({ task, group: null })), ...grouped];
  return definitions.length === 1 ? definitions[0] : null;
}

export function editableFromCloudTask(content: Record<string, unknown> | undefined): EditableTask | null {
  const definition = unpackCloudTask(content);
  return definition ? { ...fieldsFromDst1(definition.task), groupId: definition.group?.i ?? null } : null;
}

function naturalDate(timeZone: string): string {
  try {
    const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
    const values = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
    if (values.year && values.month && values.day) return `${values.year}-${values.month}-${values.day}`;
  } catch { throw new Error("空间时区无效，请刷新空间信息后重试"); }
  throw new Error("无法计算空间自然日");
}

export function buildCloudTaskContent(taskId: string, value: EditableTask, groups: GroupRecord[], timeZone?: string): Dst1Batch {
  const draft = { ...value, taskId, draftItemId: createLocalId("cloud"), source: "existing" as const };
  const rawTask = draftTaskToDst1(draft);
  const needsDate = value.taskDateIntent !== "preserve" && (value.recurrence ? !rawTask.x?.s : !rawTask.y);
  if (needsDate && !timeZone) throw new Error("空间时区尚未加载，请刷新空间信息后重试");
  const task = value.recurrence
    ? value.taskDateIntent === "preserve" || !rawTask.x || rawTask.x.s ? rawTask : { ...rawTask, x: { ...rawTask.x, s: naturalDate(timeZone!) } }
    : value.taskDateIntent === "preserve" || rawTask.y ? rawTask : { ...rawTask, y: naturalDate(timeZone!) };
  const group = value.groupId ? groups.find((item) => item.id === value.groupId) : undefined;
  if (!group) return { v: 1, b: createTransportId(), t: [task] };
  return {
    v: 1,
    b: createTransportId(),
    g: [{ i: group.id, n: group.name.trim().normalize("NFC"), cm: group.completeMessage.trim().normalize("NFC"), im: group.incompleteMessage.trim().normalize("NFC"), t: [task] }],
  };
}
