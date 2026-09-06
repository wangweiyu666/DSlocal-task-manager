import { ApiError } from "./http";
import validateSchema from "../../shared/protocol/schema-validator";
import { validateDst1Batch } from "../../shared/protocol/validation";
import type { SchemaValidator } from "../../shared/protocol/validation";

export const COMMAND_TYPES = [
  "GROUP_UPSERT",
  "GROUP_ARCHIVE",
  "SPACE_TIMEZONE_UPDATE",
  "TASK_PUBLISH",
  "TASK_UPDATE",
  "TASK_CANCEL",
  "TASK_ARCHIVE",
  "TASK_RESTORE",
  "OCCURRENCE_UPSERT",
  "EXECUTION_EVENT",
  "INFORMATION_SUBMISSION",
  "RESULT_SELECT",
  "NOTIFICATION_READ",
] as const;

export type CommandType = typeof COMMAND_TYPES[number];
export type CommandStatus = "accepted" | "duplicate" | "conflict" | "rejected" | "retryable";

export interface SyncCommand {
  commandId: string;
  entityId: string;
  baseVersion: number;
  createdAt: string;
  type: CommandType;
  payload: Record<string, unknown>;
}

export interface CommandResult {
  commandId: string;
  status: CommandStatus;
  code?: string;
  entityVersion?: number;
  changeSequence?: number;
  details?: Record<string, unknown>;
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const dstId = /^[A-Za-z0-9_-]{16}$/;
const occurrence = /^[A-Za-z0-9_-]{16}:[1-9][0-9]*:[1-9][0-9]*:[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2})?$/;

export function object(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ApiError(400, "INVALID_REQUEST", `${label} 必须是对象`);
  return value as Record<string, unknown>;
}

export function strictObject(value: unknown, allowed: readonly string[], label: string): Record<string, unknown> {
  const record = object(value, label);
  if (Object.keys(record).some((key) => !allowed.includes(key))) throw new ApiError(400, "INVALID_REQUEST", `${label} 包含未知字段`);
  return record;
}

export function stringField(body: Record<string, unknown>, key: string, max = 100_000): string {
  const value = body[key];
  if (typeof value !== "string" || value.length === 0 || value.length > max) throw new ApiError(400, "INVALID_REQUEST", `${key} 无效`);
  return value.normalize("NFC");
}

export function optionalString(body: Record<string, unknown>, key: string, max = 100_000): string | null {
  if (body[key] === undefined || body[key] === null) return null;
  return stringField(body, key, max);
}

export function integerField(body: Record<string, unknown>, key: string, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  const value = body[key];
  if (!Number.isInteger(value) || Number(value) < min || Number(value) > max) throw new ApiError(400, "INVALID_REQUEST", `${key} 无效`);
  return Number(value);
}

export function parseCommands(value: unknown): SyncCommand[] {
  const root = strictObject(value, ["commands"], "请求");
  if (!Array.isArray(root.commands) || root.commands.length < 1 || root.commands.length > 100) throw new ApiError(400, "INVALID_REQUEST", "commands 数量无效");
  return root.commands.map((raw, index) => {
    const body = strictObject(raw, ["commandId", "entityId", "baseVersion", "createdAt", "type", "payload"], `commands[${index}]`);
    const commandId = stringField(body, "commandId", 64);
    const entityId = stringField(body, "entityId", 160);
    const type = stringField(body, "type", 64) as CommandType;
    const createdAt = stringField(body, "createdAt", 40);
    if (!uuid.test(commandId)) throw new ApiError(400, "INVALID_REQUEST", `commands[${index}].commandId 无效`);
    if (!COMMAND_TYPES.includes(type)) throw new ApiError(400, "INVALID_REQUEST", `commands[${index}].type 无效`);
    if (["GROUP_UPSERT", "GROUP_ARCHIVE", "TASK_PUBLISH", "TASK_UPDATE", "TASK_CANCEL", "TASK_ARCHIVE", "TASK_RESTORE"].includes(type) && !dstId.test(entityId)) throw new ApiError(400, "INVALID_REQUEST", `commands[${index}].entityId 必须是 DST1 ID`);
    if (["SPACE_TIMEZONE_UPDATE", "EXECUTION_EVENT", "INFORMATION_SUBMISSION", "RESULT_SELECT"].includes(type) && !uuid.test(entityId)) throw new ApiError(400, "INVALID_REQUEST", `commands[${index}].entityId 无效`);
    if (!Number.isFinite(Date.parse(createdAt))) throw new ApiError(400, "INVALID_REQUEST", `commands[${index}].createdAt 无效`);
    return { commandId, entityId, baseVersion: integerField(body, "baseVersion"), createdAt, type, payload: object(body.payload, "payload") };
  });
}

export function assertOccurrenceKey(value: string, taskId: string, revision: number, timeZoneVersion: number): void {
  if (!occurrence.test(value) || !value.startsWith(`${taskId}:${revision}:${timeZoneVersion}:`)) {
    throw new ApiError(400, "INVALID_REQUEST", "occurrenceKey 与任务版本或时区版本不匹配");
  }
}

export function assertTimeZone(value: string): void {
  try { new Intl.DateTimeFormat("en", { timeZone: value }).format(); }
  catch { throw new ApiError(400, "INVALID_REQUEST", "timeZone 无效"); }
}

export function assertDst11Content(value: unknown): Record<string, unknown> {
  const content = object(value, "content");
  if (content.v !== 1) throw new ApiError(400, "INVALID_REQUEST", "任务内容必须使用 DST1 主版本 1");
  if (content.sv !== undefined && content.sv !== 1) throw new ApiError(400, "INVALID_REQUEST", "DST1 次版本不受支持");
  singleDstTask(content);
  if (content.sv === 1 && (!Array.isArray(content.e) || content.e.length === 0)) {
    throw new ApiError(400, "INVALID_REQUEST", "DST1.1 内容必须包含非空单日例外数组");
  }
  if (content.sv === undefined && content.e !== undefined) {
    throw new ApiError(400, "INVALID_REQUEST", "DST1 内容不能包含单日例外数组");
  }
  try { validateDst1Batch(content, validateSchema as SchemaValidator); }
  catch { throw new ApiError(400, "INVALID_REQUEST", "DST1 任务内容未通过完整协议校验"); }
  validateEffectiveSteps(content);
  return content;
}

function validateEffectiveSteps(content: Record<string, unknown>): void {
  const task = singleDstTask(content);
  const validateSteps = (steps: unknown, label: string): void => {
    if (!Array.isArray(steps) || steps.length < 1 || steps.length > 50) throw new ApiError(400, "INVALID_REQUEST", `${label} 必须包含 1～50 个步骤`);
    const ids = steps.map((value, index) => {
      const step = object(value, `${label}[${index}]`);
      const id = stringField(step, "i", 16);
      if (!/^[A-Za-z0-9_-]{16}$/.test(id)) throw new ApiError(400, "INVALID_REQUEST", `${label} 步骤 ID 无效`);
      if (step.u !== undefined) {
        const execution = object(step.u, `${label}[${index}].u`);
        if (Number(execution.k) === 5) throw new ApiError(400, "INVALID_REQUEST", "步骤不能嵌套 STEPS");
      }
      return id;
    });
    if (new Set(ids).size !== ids.length) throw new ApiError(400, "INVALID_REQUEST", `${label} 步骤 ID 不能重复`);
  };
  const execution = task.u && typeof task.u === "object" ? task.u as Record<string, unknown> : undefined;
  const baseIsSteps = Number(execution?.k) === 5;
  if (baseIsSteps) validateSteps(task.s, "任务步骤");
  for (const [index, raw] of (Array.isArray(content.e) ? content.e.entries() : [])) {
    const exception = object(raw, `content.e[${index}]`);
    const hasU = Object.prototype.hasOwnProperty.call(exception, "u");
    const effectiveU = hasU ? exception.u : task.u;
    const effectiveExecution = effectiveU && typeof effectiveU === "object" ? effectiveU as Record<string, unknown> : undefined;
    if (Number(effectiveExecution?.k) === 5) {
      validateSteps(Object.prototype.hasOwnProperty.call(exception, "s") ? exception.s : task.s, `例外步骤[${index}]`);
    } else if (baseIsSteps) {
      if (!Object.prototype.hasOwnProperty.call(exception, "s") || !Array.isArray(exception.s) || exception.s.length !== 0) {
        throw new ApiError(400, "INVALID_REQUEST", "切出 STEPS 的例外必须明确清空步骤");
      }
    }
  }
}

export function singleDstTask(content: Record<string, unknown>): Record<string, unknown> {
  const tasks: Array<{ value: unknown; label: string }> = [];
  if (content.t !== undefined) {
    if (!Array.isArray(content.t)) throw new ApiError(400, "INVALID_REQUEST", "content.t 必须是数组");
    content.t.forEach((value, index) => tasks.push({ value, label: `content.t[${index}]` }));
  }
  if (content.g !== undefined) {
    if (!Array.isArray(content.g) || content.g.length === 0) throw new ApiError(400, "INVALID_REQUEST", "content.g 必须是非空数组");
    content.g.forEach((value, groupIndex) => {
      const group = object(value, `content.g[${groupIndex}]`);
      if (!dstId.test(stringField(group, "i", 16))) throw new ApiError(400, "INVALID_REQUEST", "DST1 积分组 ID 无效");
      for (const key of ["n", "cm", "im"] as const) {
        if (group[key] !== undefined && typeof group[key] !== "string") throw new ApiError(400, "INVALID_REQUEST", `content.g[${groupIndex}].${key} 无效`);
      }
      if (group.t !== undefined) {
        if (!Array.isArray(group.t)) throw new ApiError(400, "INVALID_REQUEST", `content.g[${groupIndex}].t 必须是数组`);
        group.t.forEach((task, taskIndex) => tasks.push({ value: task, label: `content.g[${groupIndex}].t[${taskIndex}]` }));
      }
    });
  }
  if (tasks.length !== 1) throw new ApiError(400, "INVALID_REQUEST", "云任务修订必须包含一个 DST1/DST1.1 任务");
  const task = object(tasks[0].value, tasks[0].label);
  if (!dstId.test(stringField(task, "i", 16))) throw new ApiError(400, "INVALID_REQUEST", "DST1 任务 ID 无效");
  return task;
}
