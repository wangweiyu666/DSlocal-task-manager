import { formatTemperature, isTemperatureTenthsC } from "../../../shared/protocol/temperature";
import type { CloudEntity } from "./types";

export { formatTemperature };
export interface TemperatureRecord {
  id: string;
  executorId: string;
  taskId: string;
  seriesId: string;
  taskName: string;
  stepName: string | null;
  occurredAt: string;
  temperatureTenthsC: number;
}
const object = (value: unknown): Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
const occurrenceId = (value: Record<string, unknown>) => JSON.stringify([value.assignmentId, value.occurrenceKey]);

/** Resolve official selections first: an adopted undo replaces its earlier measurement. */
export function latestResultSelections(entities: CloudEntity[]): Map<string, CloudEntity> {
  const selections = new Map<string, CloudEntity>();
  for (const entity of entities.filter((item) => item.entityType === "result_selection" && !item.pendingCommandId)) {
    const key = occurrenceId(entity.payload);
    const previous = selections.get(key);
    if (!previous || (entity.changeSequence ?? 0) > (previous.changeSequence ?? 0) || (entity.changeSequence ?? 0) === (previous.changeSequence ?? 0) && (entity.updatedAt > previous.updatedAt || entity.updatedAt === previous.updatedAt && entity.entityVersion > previous.entityVersion)) selections.set(key, entity);
  }
  return selections;
}

export function temperatureRecords(entities: CloudEntity[]): TemperatureRecord[] {
  const selections = latestResultSelections(entities);
  const assignments = new Map(entities.filter((item) => item.entityType === "assignment").map((item) => [item.entityId, item.payload]));
  const occurrences = new Map(entities.filter((item) => item.entityType === "occurrence").map((item) => [occurrenceId(item.payload), item.payload]));
  return entities.filter((item) => item.entityType === "execution_event").flatMap((event): TemperatureRecord[] => {
    const payload = event.payload;
    if (selections.get(occurrenceId(payload))?.payload.executionEventId !== event.entityId || payload.eventType === "COMPLETION_UNDONE") return [];
    const data = object(payload.data);
    if (data.status !== "COMPLETED" || typeof payload.occurredAt !== "string" || !Number.isFinite(Date.parse(payload.occurredAt))) return [];
    const assignment = assignments.get(String(payload.assignmentId));
    const occurrence = occurrences.get(occurrenceId(payload));
    const executorId = assignment?.executorMembershipId;
    const taskId = assignment?.taskId ?? occurrence?.taskId;
    if (typeof executorId !== "string" || typeof taskId !== "string") return [];
    const base = { executorId, taskId, taskName: String(data.taskName ?? "未命名任务"), occurredAt: payload.occurredAt };
    if (data.executionKind === "TEMPERATURE" && isTemperatureTenthsC(data.temperatureTenthsC)) {
      return [{ ...base, id: event.entityId, seriesId: JSON.stringify([taskId, null]), stepName: null, temperatureTenthsC: data.temperatureTenthsC }];
    }
    if (data.executionKind !== "STEPS" || !Array.isArray(data.stepResults)) return [];
    return data.stepResults.flatMap((value): TemperatureRecord[] => {
      const step = object(value);
      if (step.status !== "CONFIRMED" || object(step.execution).k !== 8 || typeof step.stepId !== "string" || !isTemperatureTenthsC(step.temperatureTenthsC)) return [];
      return [{ ...base, id: `${event.entityId}:${step.stepId}`, seriesId: JSON.stringify([taskId, step.stepId]), stepName: String(step.name ?? "体温步骤"), temperatureTenthsC: step.temperatureTenthsC }];
    });
  }).sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || a.id.localeCompare(b.id));
}

export function temperatureDate(value: Date | string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date(value));
  const fields = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${fields.year}-${fields.month}-${fields.day}`;
}

export function temperatureRange(days: number, timeZone: string, now = new Date()): { start: string; end: string } {
  const end = temperatureDate(now, timeZone);
  const start = new Date(`${end}T12:00:00Z`);
  start.setUTCDate(start.getUTCDate() - days + 1);
  return { start: start.toISOString().slice(0, 10), end };
}

export function filterTemperatures(records: TemperatureRecord[], executorId: string, taskId: string, start: string, end: string, timeZone: string): TemperatureRecord[] {
  if (!start || !end || start > end) return [];
  return records.filter((item) => item.executorId === executorId && (taskId === "all" || item.taskId === taskId) && (() => {
    const date = temperatureDate(item.occurredAt, timeZone);
    return date >= start && date <= end;
  })());
}

export function temperatureSummary(records: TemperatureRecord[]) {
  const values = records.map((item) => item.temperatureTenthsC);
  return { count: values.length, latest: records.at(-1)?.temperatureTenthsC ?? null,
    min: values.length ? Math.min(...values) : null, max: values.length ? Math.max(...values) : null,
    mean: values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null };
}
