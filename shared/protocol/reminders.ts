import { Dst1ProtocolError, type Dst1Task, type Dst11Exception } from "./types";

const timePattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/u;
const dateTimePattern = /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/u;
function invalid(path: string, message: string): never {
  throw new Dst1ProtocolError("INVALID_DATE", path, message);
}
export function validReminderDateTime(value: string): boolean {
  if (!dateTimePattern.test(value)) return false;
  const date = value.slice(0, 10);
  const parsed = new Date(date + "T00:00:00Z");
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date;
}
function nextDay(date: string): string {
  const parsed = new Date(date + "T00:00:00Z");
  parsed.setUTCDate(parsed.getUTCDate() + 1);
  return parsed.toISOString().slice(0, 10);
}
export function reminderForTaskDay(date: string, time: string): string {
  return (time < "04:00" ? nextDay(date) : date) + "T" + time;
}
function recurringDeadline(task: Dst1Task, date: string): string | null {
  const time = task.x?.t;
  if (time === null) return null;
  if (time === undefined) return nextDay(date) + "T04:00";
  return (time <= "04:00" ? nextDay(date) : date) + "T" + time;
}
function checkOrder(reminder: string | null | undefined, deadline: string | null | undefined, path: string) {
  if (reminder && deadline && reminder > deadline) {
    throw new Dst1ProtocolError("CONFLICTING_FIELDS", path, "自定义提醒不能晚于截止时间");
  }
}
export function validateTaskReminder(task: Dst1Task, path = "a", effectiveTaskDate?: string): void {
  if (task.a === undefined) return;
  if (task.x) {
    if (!timePattern.test(task.a)) invalid(path, "重复任务的自定义提醒必须为 HH:mm");
    const date = "2000-01-01";
    checkOrder(reminderForTaskDay(date, task.a), recurringDeadline(task, date), path);
  } else {
    if (!validReminderDateTime(task.a)) invalid(path, "自定义提醒日期时间无效");
    const date = task.y ?? effectiveTaskDate;
    const deadline = task.l === null ? null : task.l
      ? (task.l.includes("T") ? task.l : nextDay(task.l) + "T04:00")
      : date ? nextDay(date) + "T04:00" : undefined;
    checkOrder(task.a, deadline, path);
  }
}
export function validateExceptionReminder(exception: Dst11Exception, base?: Dst1Task, path = "a"): void {
  if (exception.c === 1) return;
  if (exception.a != null && !validReminderDateTime(exception.a)) invalid(path, "单日自定义提醒日期时间无效");
  const reminder = exception.a !== undefined ? exception.a
    : base?.a ? reminderForTaskDay(exception.y, base.a) : undefined;
  const deadline = exception.l !== undefined ? exception.l : base ? recurringDeadline(base, exception.y) : undefined;
  checkOrder(reminder, deadline, path);
}
