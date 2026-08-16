import Ajv2020, { type ErrorObject } from "ajv/dist/2020";
import schema from "../../../docs/dst1-schema.json";
import { Dst1ProtocolError, type Dst11Exception, type Dst1Batch, type Dst1ErrorCode, type Dst1Group, type Dst1Task } from "./types";

const ajv = new Ajv2020({ allErrors: true, strict: false, validateFormats: false });
const validateSchema = ajv.compile<Dst1Batch>(schema);

function errorPath(error: ErrorObject): string {
  const base = error.instancePath
    ? error.instancePath.slice(1).replaceAll("/", ".").replace(/\.(\d+)(?=\.|$)/gu, "[$1]")
    : "$";
  if (error.keyword === "required") return `${base === "$" ? "" : `${base}.`}${String(error.params.missingProperty)}`;
  if (error.keyword === "additionalProperties") return `${base === "$" ? "" : `${base}.`}${String(error.params.additionalProperty)}`;
  return base;
}

function mapAjvError(error: ErrorObject): Dst1ErrorCode {
  if (error.keyword === "additionalProperties") return "UNKNOWN_FIELD";
  if (error.keyword === "required") return "REQUIRED_FIELD_MISSING";
  if (error.keyword === "type") return "TYPE_MISMATCH";
  if (error.keyword === "const" || error.keyword === "enum") return "INVALID_VALUE";
  if (error.keyword === "uniqueItems") return "DUPLICATE_VALUE";
  if (error.keyword === "not" || error.keyword === "oneOf") return "CONFLICTING_FIELDS";
  return "VALUE_OUT_OF_RANGE";
}

function fail(code: Dst1ErrorCode, path: string, message: string): never {
  throw new Dst1ProtocolError(code, path, message);
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function validateTaskRules(task: Dst1Task, path: string): void {
  for (const [field, value] of [["y", task.y], ["l", typeof task.l === "string" ? task.l.slice(0, 10) : undefined]] as const) {
    if (value !== undefined && !validDate(value)) fail("INVALID_DATE", `${path}.${field}`, "日期无效");
  }
  if (typeof task.l === "string" && task.l.includes("T") && !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/u.test(task.l)) {
    fail("INVALID_DATE", `${path}.l`, "日期时间无效");
  }
  if (task.h) {
    if (typeof task.l !== "string") fail("CONFLICTING_FIELDS", `${path}.h`, "提醒需要明确截止时间");
    if (new Set(task.h).size !== task.h.length || task.h.some((value, index) => index > 0 && task.h![index - 1] <= value)) {
      fail("DUPLICATE_VALUE", `${path}.h`, "提醒必须唯一并按降序排列");
    }
  }
  if (task.x) {
    if (task.x.s && !validDate(task.x.s)) fail("INVALID_DATE", `${path}.x.s`, "重复开始日期无效");
    if (task.x.e && !validDate(task.x.e)) fail("INVALID_DATE", `${path}.x.e`, "重复结束日期无效");
    if (task.x.e !== undefined && task.x.c !== undefined) fail("CONFLICTING_FIELDS", `${path}.x`, "重复规则不能同时设置结束日期和次数");
    if (task.x.f === 2 && (!task.x.w || task.x.w.length === 0)) fail("REQUIRED_FIELD_MISSING", `${path}.x.w`, "每周重复需要星期");
    if (task.x.f === 1 && task.x.w !== undefined) fail("CONFLICTING_FIELDS", `${path}.x.w`, "每日重复不能设置星期");
    if (task.x.w && (new Set(task.x.w).size !== task.x.w.length || task.x.w.some((value, index) => index > 0 && task.x!.w![index - 1] >= value))) {
      fail("DUPLICATE_VALUE", `${path}.x.w`, "星期必须唯一并按升序排列");
    }
  }
}

function validateExceptionRules(value: Dst11Exception, path: string): void {
  if (!validDate(value.y)) fail("INVALID_DATE", `${path}.y`, "例外日期无效");
  if (typeof value.l === "string" && !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/u.test(value.l)) {
    fail("INVALID_DATE", `${path}.l`, "单日截止时间必须精确到分钟");
  }
  if (typeof value.l === "string" && !validDate(value.l.slice(0, 10))) fail("INVALID_DATE", `${path}.l`, "单日截止日期无效");
  if (value.h) {
    if (value.l === null) fail("CONFLICTING_FIELDS", `${path}.h`, "永不截止的单日例外不能设置提醒");
    if (new Set(value.h).size !== value.h.length || value.h.some((item, index) => index > 0 && value.h![index - 1] <= item)) {
      fail("DUPLICATE_VALUE", `${path}.h`, "提醒必须唯一并按降序排列");
    }
  }
}

function walkStrings(value: unknown, path = "$", key = ""): void {
  if (typeof value === "string") {
    if (!/^[ibtz]$/u.test(key) && value !== value.normalize("NFC")) fail("NON_CANONICAL_TEXT", path, "文本必须使用 Unicode NFC");
    return;
  }
  if (Array.isArray(value)) value.forEach((item, index) => walkStrings(item, `${path}[${index}]`, key));
  else if (value && typeof value === "object") {
    for (const [childKey, child] of Object.entries(value)) walkStrings(child, path === "$" ? childKey : `${path}.${childKey}`, childKey);
  }
}

export function validateDst1Batch(value: unknown): asserts value is Dst1Batch {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("TYPE_MISMATCH", "$", "顶层必须是对象");
  const candidate = value as Partial<Dst1Batch>;
  if (Array.isArray(candidate.g) && candidate.g.length === 0) fail("VALUE_OUT_OF_RANGE", "g", "积分组数组不能为空");
  if (Array.isArray(candidate.t) && candidate.t.length === 0) fail("VALUE_OUT_OF_RANGE", "t", "任务数组不能为空");
  if (Array.isArray(candidate.z) && candidate.z.length === 0) fail("VALUE_OUT_OF_RANGE", "z", "撤销数组不能为空");
  if (Array.isArray(candidate.e) && candidate.e.length === 0) fail("VALUE_OUT_OF_RANGE", "e", "单日例外数组不能为空");
  const rawTaskEntries: Array<[Record<string, unknown>, string]> = [];
  if (Array.isArray(candidate.g)) candidate.g.forEach((group, groupIndex) => {
    if (group && typeof group === "object" && Array.isArray((group as Dst1Group).t)) (group as Dst1Group).t!.forEach((task, taskIndex) => {
      if (task && typeof task === "object") rawTaskEntries.push([task as unknown as Record<string, unknown>, `g[${groupIndex}].t[${taskIndex}]`]);
    });
  });
  if (Array.isArray(candidate.t)) candidate.t.forEach((task, taskIndex) => { if (task && typeof task === "object") rawTaskEntries.push([task as unknown as Record<string, unknown>, `t[${taskIndex}]`]); });
  for (const [task, path] of rawTaskEntries) {
    if (Array.isArray(task.h)) {
      const reminders = task.h;
      if (new Set(reminders).size !== reminders.length || reminders.some((value, index) => index > 0 && typeof value === "number" && typeof reminders[index - 1] === "number" && (reminders[index - 1] as number) <= value)) {
        fail("DUPLICATE_VALUE", `${path}.h`, "提醒必须唯一并按降序排列");
      }
      if (typeof task.l !== "string") fail("CONFLICTING_FIELDS", `${path}.h`, "提醒需要明确截止时间");
    }
    if (task.u && typeof task.u === "object" && !Array.isArray(task.u)) {
      const execution = task.u as Record<string, unknown>;
      if ((execution.k === 2 || execution.k === 3) && "a" in execution) fail("CONFLICTING_FIELDS", `${path}.u.a`, "该执行方式禁止字段 a");
      if (execution.k === 3 && "v" in execution) fail("CONFLICTING_FIELDS", `${path}.u.v`, "信息告知任务禁止字段 v");
    }
  }
  if (!candidate.d && !candidate.g?.length && !candidate.t?.length && !candidate.z?.length && !candidate.e?.length && candidate.d !== "") {
    fail("EMPTY_OPERATION", "$", "批次必须包含至少一种操作");
  }
  candidate.g?.forEach((group, index) => {
    if (group.n === undefined && group.cm === undefined && group.im === undefined && !group.t?.length) {
      fail("EMPTY_OPERATION", `g[${index}]`, "积分组更新不能为空");
    }
  });
  if (!validateSchema(value)) {
    const error = validateSchema.errors?.[0];
    if (!error) fail("INVALID_VALUE", "$", "DST1 数据无效");
    fail(mapAjvError(error), errorPath(error), error.message ?? "DST1 数据无效");
  }
  const batch = value as Dst1Batch;
  walkStrings(batch);
  const groupIds = batch.g?.map((group) => group.i) ?? [];
  if (new Set(groupIds).size !== groupIds.length) fail("DUPLICATE_VALUE", "g", "积分组 ID 重复");
  const taskEntries: Array<[Dst1Task, string]> = [];
  batch.g?.forEach((group, groupIndex) => group.t?.forEach((task, taskIndex) => taskEntries.push([task, `g[${groupIndex}].t[${taskIndex}]`])));
  batch.t?.forEach((task, index) => taskEntries.push([task, `t[${index}]`]));
  if (taskEntries.length > 100) fail("VALUE_OUT_OF_RANGE", "t", "一个批次最多包含 100 个任务");
  const taskIds = taskEntries.map(([task]) => task.i);
  if (new Set(taskIds).size !== taskIds.length) fail("DUPLICATE_VALUE", "t", "任务 ID 重复");
  if (batch.z?.some((id) => taskIds.includes(id))) fail("CONFLICTING_FIELDS", "z", "任务不能同时更新和撤销");
  const exceptionKeys = batch.e?.map((item) => `${item.i}|${item.y}`) ?? [];
  if (new Set(exceptionKeys).size !== exceptionKeys.length) fail("DUPLICATE_VALUE", "e", "同一任务日期不能重复出现单日例外");
  if (batch.e?.some((item) => batch.z?.includes(item.i))) fail("CONFLICTING_FIELDS", "e", "任务不能同时整项撤销和设置单日例外");
  batch.e?.forEach((item, index) => validateExceptionRules(item, `e[${index}]`));
  taskEntries.forEach(([task, path]) => validateTaskRules(task, path));
}
