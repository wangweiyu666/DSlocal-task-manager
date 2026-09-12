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
  moodRating: number | null;
  moodText: string | null;
  moodLabel: string | null;
  reviewMessage: string | null;
  duplicateMessage: string | null;
  stepResults: StepResultPresentation[];
  noticeContent: string | null;
  selectedOptionName: string | null;
  optionPoints: number | null;
  awardedPoints: number | null;
}

export interface StepResultPresentation {
  stepId: string;
  name: string;
  required: boolean;
  execution: Record<string, unknown> | null;
  status: "CONFIRMED" | "SKIPPED" | "NOT_APPLICABLE";
  answer: string;
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function stepAnswer(row: Record<string, unknown>, execution: Record<string, unknown>, skipped: boolean): string {
  if (skipped) return "";
  const kind = execution.k;
  if (kind === 1 && Number.isFinite(row.counterValue)) return `完成 ${Number(row.counterValue)} / ${Number(execution.v)} 次`;
  if (kind === 2 && Number.isFinite(row.elapsedMillis)) return `用时 ${(Number(row.elapsedMillis) / 1000).toFixed(1)} / ${Number(execution.v)} 秒`;
  if (kind === 3) return nonEmptyString(row.informationContent) ?? "已填写";
  if (kind === 4 && Number.isInteger(row.moodRating) && Number(row.moodRating) >= 1 && Number(row.moodRating) <= 5) return `${["很差", "较差", "一般", "不错", "很好"][Number(row.moodRating) - 1]}${nonEmptyString(row.moodText) ? ` · ${row.moodText}` : ""}`;
  if (kind === 6) return `已知晓 · ${String(execution.t ?? "")}`;
  if (kind === 7) return `${String(row.selectedOptionName ?? "已选择")} · +${Number(row.optionPoints ?? 0)} 分`;
  return "已完成";
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
  const informationContent = eventType === "COMPLETION_UNDONE" || ["MOOD", "NOTICE", "CHOICE"].includes(String(data.executionKind))
    ? null
    : data.executionKind === "STEPS" ? null : nonEmptyString(data.informationContent) ?? nonEmptyString(separateSubmission?.payload.content);
  const stepResults: StepResultPresentation[] = eventType !== "COMPLETION_UNDONE" && data.executionKind === "STEPS" && status === "COMPLETED" && Array.isArray(data.stepResults)
    ? data.stepResults.flatMap((item): StepResultPresentation[] => {
      const row = record(item);
      const stepId = nonEmptyString(row.stepId);
      const name = nonEmptyString(row.name);
      const stepStatus = row.status === "CONFIRMED" || row.status === "SKIPPED" || row.status === "NOT_APPLICABLE" ? row.status : null;
      if (!stepId || !name || !stepStatus || typeof row.required !== "boolean") return [];
      const execution = row.execution === null ? {} : record(row.execution);
      return [{ stepId, name, required: row.required, execution: row.execution === null ? null : execution, status: stepStatus, answer: stepAnswer(row, execution, stepStatus !== "CONFIRMED") }];
    }) : [];
  const reviewReason = nonEmptyString(result.payload.reviewReason);
  const moodRating = eventType !== "COMPLETION_UNDONE" && data.status === "COMPLETED" && data.executionKind === "MOOD" && Number.isInteger(data.moodRating) && Number(data.moodRating) >= 1 && Number(data.moodRating) <= 5 ? Number(data.moodRating) : null;

  return {
    taskName,
    statusLabel: statusLabels[status] ?? "状态待确认",
    eventLabel: eventLabels[eventType] ?? "执行者提交了任务动态",
    taskDate: localDateLabel(data.taskDate ?? occurrence?.payload.localDate),
    occurredAt: dateTimeLabel(result.payload.occurredAt, timeZone),
    taskRevision: Number.isInteger(result.payload.taskRevision) ? Number(result.payload.taskRevision) : null,
    informationContent,
    moodRating,
    moodText: moodRating !== null && typeof data.moodText === "string" ? data.moodText : null,
    moodLabel: moodRating === null ? null : ["很差", "较差", "一般", "不错", "很好"][moodRating - 1],
    reviewMessage: reviewReason ? reviewMessages[reviewReason] ?? "这条结果需要管理员核对后采用。" : null,
    duplicateMessage: nonEmptyString(result.payload.duplicateOf) ? "同一任务已有更早提交，本条作为候选结果保留。" : null,
    stepResults,
    noticeContent: status === "COMPLETED" && eventType !== "COMPLETION_UNDONE" ? nonEmptyString(data.noticeContent) : null,
    selectedOptionName: status === "COMPLETED" && eventType !== "COMPLETION_UNDONE" ? nonEmptyString(data.selectedOptionName) : null,
    optionPoints: status === "COMPLETED" && eventType !== "COMPLETION_UNDONE" && Number.isInteger(data.optionPoints) ? Number(data.optionPoints) : null,
    awardedPoints: status === "COMPLETED" && eventType !== "COMPLETION_UNDONE" && Number.isInteger(data.awardedPoints) ? Number(data.awardedPoints) : null,
  };
}
