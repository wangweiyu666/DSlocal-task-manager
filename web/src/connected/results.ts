import { unpackCloudTask } from "./library";
import type { CloudEntity } from "./types";

export interface ExecutionResultPresentation {
  taskName: string;
  statusLabel: string;
  eventLabel: string;
  taskDate: string | null;
  occurredAt: string;
  taskRevision: number | null;
  informationContent: string | null;
  reviewMessage: string | null;
  duplicateMessage: string | null;
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function localDateLabel(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(value);
  return match ? `${Number(match[1])}年${Number(match[2])}月${Number(match[3])}日` : null;
}

function dateTimeLabel(value: unknown, timeZone: string): string {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) return "提交时间未知";
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      timeZone,
      year: "numeric",
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date(value));
  } catch {
    return new Intl.DateTimeFormat("zh-CN", {
      year: "numeric",
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date(value));
  }
}

const statusLabels: Record<string, string> = {
  NOT_STARTED: "未开始",
  COMPLETED: "已完成",
  MISSED: "未完成",
  PENDING: "待完成",
  CANCELLED: "已取消",
};

const eventLabels: Record<string, string> = {
  COMPLETION_UNDONE: "执行者撤销了任务完成状态",
  RESULT_SUBMITTED: "执行者提交了任务结果",
  COMPLETED: "执行者完成了任务",
  CORRECTION: "执行者更正了任务结果",
};

const reviewMessages: Record<string, string> = {
  CANCELLED_AFTER_SEEN: "任务在执行者查看后被取消，请核对是否采用这条结果。",
  STALE_REVISION: "执行者使用的是较早的任务版本，请核对是否采用这条结果。",
};

export function presentExecutionResult(
  result: CloudEntity,
  entities: CloudEntity[],
  timeZone: string,
): ExecutionResultPresentation {
  const data = record(result.payload.data);
  const occurrenceKey = nonEmptyString(result.payload.occurrenceKey);
  const assignmentId = nonEmptyString(result.payload.assignmentId);
  const occurrence = occurrenceKey && assignmentId
    ? entities.find((item) => item.entityType === "occurrence" && item.payload.occurrenceKey === occurrenceKey && item.payload.assignmentId === assignmentId)
    : undefined;
  const taskId = nonEmptyString(occurrence?.payload.taskId);
  const task = taskId ? entities.find((item) => item.entityType === "task" && item.entityId === taskId) : undefined;
  const definition = unpackCloudTask(task?.payload.content as Record<string, unknown> | undefined);
  const taskName = nonEmptyString(data.taskName) ?? definition?.task.n ?? "未命名任务";
  const status = nonEmptyString(data.status) ?? "UNKNOWN";
  const eventType = nonEmptyString(result.payload.eventType) ?? "UNKNOWN";
  const separateSubmission = occurrenceKey && assignmentId
    ? entities.filter((item) => item.entityType === "information_submission" && item.payload.occurrenceKey === occurrenceKey && item.payload.assignmentId === assignmentId)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0]
    : undefined;
  const informationContent = eventType === "COMPLETION_UNDONE"
    ? null
    : nonEmptyString(data.informationContent) ?? nonEmptyString(separateSubmission?.payload.content);
  const reviewReason = nonEmptyString(result.payload.reviewReason);

  return {
    taskName,
    statusLabel: statusLabels[status] ?? "状态待确认",
    eventLabel: eventLabels[eventType] ?? "执行者提交了任务动态",
    taskDate: localDateLabel(data.taskDate ?? occurrence?.payload.localDate),
    occurredAt: dateTimeLabel(result.payload.occurredAt, timeZone),
    taskRevision: Number.isInteger(result.payload.taskRevision) ? Number(result.payload.taskRevision) : null,
    informationContent,
    reviewMessage: reviewReason ? reviewMessages[reviewReason] ?? "这条结果需要管理员核对后采用。" : null,
    duplicateMessage: nonEmptyString(result.payload.duplicateOf) ? "同一任务已有更早提交，本条作为候选结果保留。" : null,
  };
}
