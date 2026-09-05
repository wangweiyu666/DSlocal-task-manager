import { authenticate, requireAccountReady } from "./auth";
import { decodeCursor, encodeCursor } from "./cursor";
import { addSeconds, sha256, uuidV7 } from "./crypto";
import { ApiError, json, requestId } from "./http";
import { membership } from "./spaces";
import {
  assertDst11Content,
  assertOccurrenceKey,
  assertTimeZone,
  integerField,
  optionalString,
  parseCommands,
  singleDstTask,
  strictObject,
  stringField,
  type CommandResult,
  type SyncCommand,
} from "./sync-contract";
import type { Env, SessionPrincipal } from "./types";
import { recordUsage, serviceQuotaSnapshot } from "./usage";

interface MembershipRow { id: string; role: "ADMIN" | "EXECUTOR"; space_id: string; status: string; name: string; time_zone: string; time_zone_version: number; current_sequence: number; }
type AssignmentMode = "ALL" | "SELECTED";
interface TaskRow { id: string; current_revision: number; status: "ACTIVE" | "CANCELLED" | "ARCHIVED"; content_json: string; assignment_mode: AssignmentMode; }

interface CommandEnv extends Env { writes: D1PreparedStatement[]; }
function stage(env: CommandEnv, statements: D1PreparedStatement[]): void { env.writes.push(...statements); }

const DAY = 86_400;
const iso = () => new Date().toISOString();

function stable(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.entries(value as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => `${JSON.stringify(key)}:${stable(item)}`).join(",")}}`;
  return JSON.stringify(value);
}

function parseJson(value: string): Record<string, unknown> {
  try { return JSON.parse(value) as Record<string, unknown>; }
  catch { throw new ApiError(500, "INTERNAL_ERROR", "服务器保存的同步数据无效", true); }
}

function receiptStatus(result: CommandResult): "ACCEPTED" | "CONFLICT" | "REJECTED" {
  if (result.status === "accepted" || result.status === "duplicate") return "ACCEPTED";
  return result.status === "conflict" ? "CONFLICT" : "REJECTED";
}

async function currentSequence(env: Env, spaceId: string): Promise<number> {
  const row = await env.DB.prepare("SELECT current_sequence FROM spaces WHERE id=?").bind(spaceId).first<{ current_sequence: number }>();
  if (!row) throw new ApiError(404, "NOT_FOUND", "空间不存在");
  return row.current_sequence;
}

function entityStatements(env: Env, spaceId: string, entityType: string, entityId: string, version: number, payload: unknown, now: string): D1PreparedStatement[] {
  const payloadJson = JSON.stringify(payload);
  return [
    env.DB.prepare("UPDATE spaces SET current_sequence=current_sequence+1 WHERE id=?").bind(spaceId),
    env.DB.prepare("INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence) VALUES (?,?,?,?,1,?,?,(SELECT current_sequence FROM spaces WHERE id=?)) ON CONFLICT(space_id,entity_type,entity_id) DO UPDATE SET entity_version=excluded.entity_version,payload_version=excluded.payload_version,payload_json=excluded.payload_json,updated_at=excluded.updated_at,change_sequence=excluded.change_sequence")
      .bind(spaceId, entityType, entityId, version, payloadJson, now, spaceId),
    env.DB.prepare("INSERT INTO space_changes(space_id,sequence,entity_type,entity_id,operation,entity_version,payload_json,created_at,expires_at) SELECT id,current_sequence,?,?, 'UPSERT',?,?,?,? FROM spaces WHERE id=?")
      .bind(entityType, entityId, version, payloadJson, now, addSeconds(now, 90 * DAY), spaceId),
  ];
}

async function notification(env: CommandEnv, spaceId: string, role: "ADMIN" | "EXECUTOR", type: string, entityId: string, groupKey: string, payload: unknown, now: string, recipientMembershipId?: string): Promise<Record<string, unknown> | null> {
  const recipients = recipientMembershipId
    ? await env.DB.prepare("SELECT id FROM memberships WHERE id=? AND space_id=? AND role=? AND status='ACTIVE'").bind(recipientMembershipId, spaceId, role).all<{ id: string }>()
    : await env.DB.prepare("SELECT id FROM memberships WHERE space_id=? AND role=? AND status='ACTIVE' ORDER BY joined_at,id").bind(spaceId, role).all<{ id: string }>();
  if (!recipients.results.length) return null;
  const values = recipients.results.map((recipient) => ({ id: uuidV7(), type, entityId, groupKey, payload, createdAt: now, recipientMembershipId: recipient.id }));
  stage(env, values.flatMap((value) => [
    env.DB.prepare("INSERT INTO notifications(id,space_id,recipient_membership_id,type,entity_id,payload_json,created_at,group_key,payload_version) VALUES (?,?,?,?,?,?,?, ?,1)")
      .bind(value.id, spaceId, value.recipientMembershipId, type, entityId, JSON.stringify(payload), now, groupKey),
    ...entityStatements(env, spaceId, "notification", value.id, 1, { ...value, readAt: null }, now),
  ]));
  return values[0];
}

function occurrenceStorageKey(assignmentId: string, occurrenceKey: string): string {
  return `${assignmentId}:${occurrenceKey}`;
}

async function audit(env: CommandEnv, principal: SessionPrincipal, memberId: string, spaceId: string, eventType: string, entityType: string, entityId: string, metadata: unknown, request: Request, now: string): Promise<void> {
  stage(env, [env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
    .bind(uuidV7(), spaceId, principal.accountId, memberId, eventType, entityType, entityId, JSON.stringify(metadata), requestId(request), now)]);
}

function receiptStatement(env: Env, principal: SessionPrincipal, spaceId: string, hash: string, result: CommandResult): D1PreparedStatement {
  const createdAt = iso();
  return env.DB.prepare("INSERT INTO command_receipts(command_id,space_id,session_id,payload_hash,status,response_json,created_at,expires_at) SELECT ?,?,?,?,?,CASE WHEN ?='accepted' THEN json_set(?, '$.changeSequence', current_sequence) ELSE ? END,?,? FROM spaces WHERE id=?")
    .bind(result.commandId, spaceId, principal.sessionId, hash, receiptStatus(result), result.status, JSON.stringify(result), JSON.stringify(result), createdAt, addSeconds(createdAt, 90 * DAY), spaceId);
}

function replayReceipt(commandId: string, original: CommandResult): CommandResult {
  // A duplicate delivery must not turn a previously rejected/conflicting write into success.
  return { ...original, commandId, status: original.status === "accepted" ? "duplicate" : original.status };
}

async function executeGroup(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const now = iso();
  const payload = strictObject(command.payload, command.type === "GROUP_UPSERT" ? ["content"] : [], "payload");
  const existing = await env.DB.prepare("SELECT current_revision,status FROM task_groups WHERE id=? AND space_id=?").bind(command.entityId, spaceId).first<{ current_revision: number; status: string }>();
  if (command.type === "GROUP_ARCHIVE") {
    if (!existing) throw new ApiError(404, "NOT_FOUND", "任务组不存在");
    if (existing.current_revision !== command.baseVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "任务组版本冲突");
    const value = { id: command.entityId, version: existing.current_revision, status: "ARCHIVED" };
    stage(env, [
      env.DB.prepare("UPDATE task_groups SET status='ARCHIVED',updated_at=? WHERE id=? AND space_id=? AND current_revision=?").bind(now, command.entityId, spaceId, command.baseVersion),
      ...entityStatements(env, spaceId, "task_group", command.entityId, existing.current_revision, value, now),
    ]);
    await audit(env, principal, memberId, spaceId, "GROUP_ARCHIVED", "task_group", command.entityId, {}, request, now);
    return { commandId: command.commandId, status: "accepted", entityVersion: existing.current_revision, changeSequence: await currentSequence(env, spaceId) };
  }
  const content = strictObject(payload.content, ["id", "name", "completeMessage", "incompleteMessage", "order"], "content");
  if (stringField(content, "id", 64) !== command.entityId) throw new ApiError(400, "INVALID_REQUEST", "任务组 ID 不匹配");
  stringField(content, "name", 80);
  const version = existing ? existing.current_revision + 1 : 1;
  if ((existing?.current_revision ?? 0) !== command.baseVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "任务组版本冲突");
  const contentJson = stable(content);
  const value = { id: command.entityId, version, status: "ACTIVE", content };
  stage(env, [
    existing
      ? env.DB.prepare("UPDATE task_groups SET current_revision=?,status='ACTIVE',updated_at=? WHERE id=? AND space_id=? AND current_revision=?").bind(version, now, command.entityId, spaceId, command.baseVersion)
      : env.DB.prepare("INSERT INTO task_groups(id,space_id,current_revision,status,created_at,updated_at) VALUES (?,?,1,'ACTIVE',?,?)").bind(command.entityId, spaceId, now, now),
    env.DB.prepare("INSERT INTO task_group_revisions(group_id,revision,space_id,content_json,content_hash,created_by_membership_id,created_at) VALUES (?,?,?,?,?,?,?)")
      .bind(command.entityId, version, spaceId, contentJson, await sha256(contentJson), memberId, now),
    ...entityStatements(env, spaceId, "task_group", command.entityId, version, value, now),
  ]);
  await audit(env, principal, memberId, spaceId, existing ? "GROUP_UPDATED" : "GROUP_CREATED", "task_group", command.entityId, { version }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: version, changeSequence: await currentSequence(env, spaceId) };
}

async function executeTimezone(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["timeZone", "effectiveAt"], "payload");
  const timeZone = stringField(payload, "timeZone", 80); assertTimeZone(timeZone);
  const effectiveAt = stringField(payload, "effectiveAt", 40);
  if (!Number.isFinite(Date.parse(effectiveAt))) throw new ApiError(400, "INVALID_REQUEST", "effectiveAt 无效");
  const space = await env.DB.prepare("SELECT time_zone_version FROM spaces WHERE id=?").bind(spaceId).first<{ time_zone_version: number }>();
  if (!space) throw new ApiError(404, "NOT_FOUND", "空间不存在");
  if (space.time_zone_version !== command.baseVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "空间时区版本冲突");
  const version = space.time_zone_version + 1; const now = iso();
  const value = { timeZone, timeZoneVersion: version, effectiveAt };
  stage(env, [
    env.DB.prepare("UPDATE spaces SET time_zone=?,time_zone_version=?,time_zone_changed_at=? WHERE id=? AND time_zone_version=?").bind(timeZone, version, effectiveAt, spaceId, command.baseVersion),
    ...entityStatements(env, spaceId, "space_timezone", spaceId, version, value, now),
  ]);
  const note = await notification(env, spaceId, "EXECUTOR", "SPACE_TIMEZONE_CHANGED", spaceId, spaceId, value, now);
  await audit(env, principal, memberId, spaceId, "SPACE_TIMEZONE_CHANGED", "space", spaceId, { version }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: version, changeSequence: await currentSequence(env, spaceId), details: note ? { notificationId: note.id } : undefined };
}

interface AssignmentChange {
  value: { id: string; executorMembershipId: string; status: "ACTIVE" | "CANCELLED" };
  statement: D1PreparedStatement;
}

function assignmentModeField(value: unknown, fallback: AssignmentMode): AssignmentMode {
  if (value === undefined) return fallback;
  if (value !== "ALL" && value !== "SELECTED") throw new ApiError(400, "INVALID_REQUEST", "assignmentMode 无效");
  return value;
}

function executorMembershipIdsField(value: unknown): string[] | undefined {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > 100 || value.some((item) => typeof item !== "string" || item.length < 1 || item.length > 64)) {
    throw new ApiError(400, "INVALID_REQUEST", "executorMembershipIds 无效");
  }
  const ids = value as string[];
  if (new Set(ids).size !== ids.length) throw new ApiError(400, "INVALID_REQUEST", "executorMembershipIds 不能重复");
  return ids;
}

async function taskAssignments(env: Env, spaceId: string, taskId: string, revision: number, now: string, mode: AssignmentMode, requestedIds: string[]): Promise<{ active: AssignmentChange[]; changes: AssignmentChange[] }> {
  const executors = await env.DB.prepare("SELECT id FROM memberships WHERE space_id=? AND role='EXECUTOR' AND status='ACTIVE' ORDER BY joined_at,id").bind(spaceId).all<{ id: string }>();
  const executorIds = new Set(executors.results.map((item) => item.id));
  const targetIds = mode === "ALL" ? executors.results.map((item) => item.id) : requestedIds;
  if (mode === "SELECTED" && targetIds.length === 0) throw new ApiError(400, "INVALID_REQUEST", "指定执行者时至少选择一名成员");
  if (targetIds.some((id) => !executorIds.has(id))) throw new ApiError(409, "CONFLICT", "所选执行者已离开空间，请刷新成员列表后重试");
  const targetSet = new Set(targetIds);
  const existing = await env.DB.prepare("SELECT id,executor_membership_id,status FROM assignments WHERE space_id=? AND task_id=?").bind(spaceId, taskId).all<{ id: string; executor_membership_id: string; status: string }>();
  const existingByMember = new Map(existing.results.map((item) => [item.executor_membership_id, item.id]));
  const active = targetIds.map((executorId) => {
    const existingId = existingByMember.get(executorId);
    const id = existingId ?? uuidV7();
    const statement = existingId
      ? env.DB.prepare("UPDATE assignments SET assigned_revision=?,status='ACTIVE',updated_at=? WHERE id=?").bind(revision, now, id)
      : env.DB.prepare("INSERT INTO assignments(id,space_id,task_id,executor_membership_id,assigned_revision,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)").bind(id, spaceId, taskId, executorId, revision, now, now);
    return { value: { id, executorMembershipId: executorId, status: "ACTIVE" as const }, statement };
  });
  const cancelled = existing.results.filter((item) => !targetSet.has(item.executor_membership_id)).map((item) => ({
    value: { id: item.id, executorMembershipId: item.executor_membership_id, status: "CANCELLED" as const },
    statement: env.DB.prepare("UPDATE assignments SET assigned_revision=?,status='CANCELLED',updated_at=? WHERE id=?").bind(revision, now, item.id),
  }));
  return { active, changes: [...active, ...cancelled] };
}

async function executeTask(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const existing = await env.DB.prepare("SELECT t.id,t.current_revision,t.status,t.assignment_mode,r.content_json FROM tasks t JOIN task_revisions r ON r.task_id=t.id AND r.revision=t.current_revision WHERE t.id=? AND t.space_id=?").bind(command.entityId, spaceId).first<TaskRow>();
  const now = iso();
  if (command.type === "TASK_CANCEL" || command.type === "TASK_ARCHIVE") {
    strictObject(command.payload, [], "payload");
    if (!existing) throw new ApiError(404, "NOT_FOUND", "任务不存在");
    if (existing.current_revision !== command.baseVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "任务版本冲突");
    const status = command.type === "TASK_CANCEL" ? "CANCELLED" : "ARCHIVED";
    const assigned = await env.DB.prepare("SELECT id,executor_membership_id FROM assignments WHERE space_id=? AND task_id=? AND status='ACTIVE' ORDER BY created_at,id").bind(spaceId, command.entityId).all<{ id: string; executor_membership_id: string }>();
    const value = { id: command.entityId, version: existing.current_revision, status, content: parseJson(existing.content_json), assignmentMode: existing.assignment_mode, executorMembershipIds: assigned.results.map((item) => item.executor_membership_id) };
    const statements: D1PreparedStatement[] = [
      env.DB.prepare("UPDATE tasks SET status=?,updated_at=? WHERE id=? AND space_id=? AND current_revision=?").bind(status, now, command.entityId, spaceId, command.baseVersion),
      env.DB.prepare("UPDATE assignments SET status=?,updated_at=? WHERE space_id=? AND task_id=? AND status='ACTIVE'").bind(status, now, spaceId, command.entityId),
      ...entityStatements(env, spaceId, "task", command.entityId, existing.current_revision, value, now),
    ];
    assigned.results.forEach((assignment) => statements.push(...entityStatements(env, spaceId, "assignment", assignment.id, existing.current_revision, { id: assignment.id, taskId: command.entityId, executorMembershipId: assignment.executor_membership_id, assignedRevision: existing.current_revision, status }, now)));
    stage(env, statements);
    let note: Record<string, unknown> | null = null;
    for (const assignment of assigned.results) {
      const delivered = await notification(env, spaceId, "EXECUTOR", `TASK_${status}`, command.entityId, command.entityId, value, now, assignment.executor_membership_id);
      if (!note) note = delivered;
    }
    await audit(env, principal, memberId, spaceId, `TASK_${status}`, "task", command.entityId, { version: existing.current_revision }, request, now);
    return { commandId: command.commandId, status: "accepted", entityVersion: existing.current_revision, changeSequence: await currentSequence(env, spaceId), details: note ? { notificationId: note.id } : undefined };
  }
  const payload = strictObject(command.payload, ["content", "assignmentMode", "executorMembershipIds"], "payload");
  const content = assertDst11Content(payload.content);
  const task = singleDstTask(content);
  if (task.i !== command.entityId) throw new ApiError(400, "INVALID_REQUEST", "DST1.1 任务 ID 不匹配");
  if (command.type === "TASK_PUBLISH" && existing) throw new ApiError(409, "CONFLICT", "任务已存在");
  if (command.type !== "TASK_PUBLISH" && !existing) throw new ApiError(404, "NOT_FOUND", "任务不存在");
  if ((existing?.current_revision ?? 0) !== command.baseVersion) throw new ApiError(409, "TASK_VERSION_CONFLICT", "任务版本冲突");
  if (command.type === "TASK_UPDATE" && existing?.status !== "ACTIVE") throw new ApiError(409, "CONFLICT", "已取消或归档任务不能普通编辑");
  if (command.type === "TASK_RESTORE" && existing?.status !== "ARCHIVED") throw new ApiError(409, "CONFLICT", "只有归档任务可以恢复");
  const version = (existing?.current_revision ?? 0) + 1;
  const contentJson = stable(content);
  const assignmentMode = assignmentModeField(payload.assignmentMode, existing?.assignment_mode ?? "ALL");
  let executorMembershipIds = executorMembershipIdsField(payload.executorMembershipIds);
  if (executorMembershipIds === undefined && assignmentMode === "SELECTED" && existing) {
    const current = await env.DB.prepare("SELECT a.executor_membership_id FROM assignments a JOIN memberships m ON m.id=a.executor_membership_id AND m.status='ACTIVE' WHERE a.space_id=? AND a.task_id=? AND a.status IN ('ACTIVE','ARCHIVED') ORDER BY a.created_at,a.id").bind(spaceId, command.entityId).all<{ executor_membership_id: string }>();
    executorMembershipIds = current.results.map((item) => item.executor_membership_id);
  }
  const assignmentPlan = await taskAssignments(env, spaceId, command.entityId, version, now, assignmentMode, executorMembershipIds ?? []);
  const assignments = assignmentPlan.active;
  const assignedIds = assignments.map((item) => item.value.executorMembershipId);
  const value = { id: command.entityId, version, status: "ACTIVE", content, assignmentMode, executorMembershipIds: assignedIds, assignment: assignments[0]?.value ?? null, assignments: assignments.map((item) => item.value) };
  const statements: D1PreparedStatement[] = [
    existing
      ? env.DB.prepare("UPDATE tasks SET current_revision=?,status='ACTIVE',assignment_mode=?,updated_at=? WHERE id=? AND space_id=? AND current_revision=?").bind(version, assignmentMode, now, command.entityId, spaceId, command.baseVersion)
      : env.DB.prepare("INSERT INTO tasks(id,space_id,current_revision,status,created_at,updated_at,assignment_mode) VALUES (?,?,1,'ACTIVE',?,?,?)").bind(command.entityId, spaceId, now, now, assignmentMode),
    env.DB.prepare("INSERT INTO task_revisions(task_id,revision,space_id,content_json,content_hash,created_by_membership_id,created_at,assignment_mode,executor_membership_ids_json) VALUES (?,?,?,?,?,?,?,?,?)")
      .bind(command.entityId, version, spaceId, contentJson, await sha256(stable({ content, assignmentMode, executorMembershipIds: assignedIds })), memberId, now, assignmentMode, JSON.stringify(assignedIds)),
  ];
  statements.push(...assignmentPlan.changes.map((item) => item.statement));
  statements.push(...entityStatements(env, spaceId, "task", command.entityId, version, value, now));
  assignmentPlan.changes.forEach((assignment) => statements.push(...entityStatements(env, spaceId, "assignment", assignment.value.id, version, { ...assignment.value, taskId: command.entityId, assignedRevision: version }, now)));
  stage(env, statements);
  let note: Record<string, unknown> | null = null;
  for (const assignment of assignments) {
    const delivered = await notification(env, spaceId, "EXECUTOR", existing ? "TASK_UPDATED" : "TASK_CREATED", command.entityId, command.entityId, { version }, now, assignment.value.executorMembershipId);
    if (!note) note = delivered;
  }
  await audit(env, principal, memberId, spaceId, existing ? "TASK_UPDATED" : "TASK_CREATED", "task", command.entityId, { version, assignmentMode, executorCount: assignedIds.length }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: version, changeSequence: await currentSequence(env, spaceId), details: { assignmentId: assignments[0]?.value.id ?? null, assignmentIds: assignments.map((item) => item.value.id), assignments: assignments.map((item) => item.value), notificationId: note?.id ?? null } };
}

async function executeOccurrence(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["taskId", "assignmentId", "occurrenceKey", "localDate", "scheduledAt", "taskRevision", "timeZoneVersion"], "payload");
  const taskId = stringField(payload, "taskId", 64); const assignmentId = stringField(payload, "assignmentId", 64);
  const occurrenceKey = stringField(payload, "occurrenceKey", 160); const taskRevision = integerField(payload, "taskRevision", 1); const timeZoneVersion = integerField(payload, "timeZoneVersion", 1);
  assertOccurrenceKey(occurrenceKey, taskId, taskRevision, timeZoneVersion);
  if (occurrenceKey !== command.entityId) throw new ApiError(400, "INVALID_REQUEST", "occurrence entityId 不匹配");
  const localDate = stringField(payload, "localDate", 10); const scheduledAt = stringField(payload, "scheduledAt", 40);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate) || !Number.isFinite(Date.parse(scheduledAt))) throw new ApiError(400, "INVALID_REQUEST", "occurrence 时间无效");
  const assignment = await env.DB.prepare("SELECT task_id,assigned_revision,status FROM assignments WHERE id=? AND space_id=? AND executor_membership_id=?").bind(assignmentId, spaceId, memberId).first<{ task_id: string; assigned_revision: number; status: string }>();
  if (!assignment || assignment.task_id !== taskId) throw new ApiError(403, "FORBIDDEN", "分配不属于当前执行者");
  const storageKey = occurrenceStorageKey(assignmentId, occurrenceKey);
  const existing = await env.DB.prepare("SELECT occurrence_key FROM task_occurrences WHERE space_id=? AND assignment_id=? AND occurrence_key IN (?,?)").bind(spaceId, assignmentId, storageKey, occurrenceKey).first();
  if (existing) return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId) };
  const now = iso();
  const value = { occurrenceKey, taskId, assignmentId, taskRevision, timeZoneVersion, localDate, scheduledAt, status: assignment.status === "ACTIVE" ? "OPEN" : "CANCELLED", generatedBy: "CLIENT" };
  stage(env, [
    env.DB.prepare("INSERT INTO task_occurrences(occurrence_key,space_id,assignment_id,task_id,task_revision,time_zone_version,local_date,scheduled_at,status,generated_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'CLIENT',?,?)")
      .bind(storageKey, spaceId, assignmentId, taskId, taskRevision, timeZoneVersion, localDate, scheduledAt, value.status, now, now),
    ...entityStatements(env, spaceId, "occurrence", storageKey, 1, value, now),
  ]);
  await audit(env, principal, memberId, spaceId, "OCCURRENCE_GENERATED", "occurrence", occurrenceKey, { taskRevision, timeZoneVersion }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId) };
}

async function executeEvent(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["assignmentId", "occurrenceKey", "taskRevision", "eventType", "data", "occurredAt"], "payload");
  const assignmentId = stringField(payload, "assignmentId", 64); const occurrenceKey = stringField(payload, "occurrenceKey", 160);
  const taskRevision = integerField(payload, "taskRevision", 1); const eventType = stringField(payload, "eventType", 64); const occurredAt = stringField(payload, "occurredAt", 40);
  const storageKey = occurrenceStorageKey(assignmentId, occurrenceKey);
  const occurrence = await env.DB.prepare("SELECT o.occurrence_key AS storage_occurrence_key,o.status,o.task_revision,o.time_zone_version,a.status AS assignment_status,t.current_revision,t.status AS task_status,s.time_zone_version AS current_time_zone_version FROM task_occurrences o JOIN assignments a ON a.id=o.assignment_id JOIN tasks t ON t.id=o.task_id JOIN spaces s ON s.id=o.space_id WHERE o.occurrence_key IN (?,?) AND o.space_id=? AND o.assignment_id=? AND a.executor_membership_id=? ORDER BY CASE WHEN o.occurrence_key=? THEN 0 ELSE 1 END LIMIT 1")
    .bind(storageKey, occurrenceKey, spaceId, assignmentId, memberId, storageKey).first<Record<string, unknown>>();
  if (!occurrence) throw new ApiError(404, "NOT_FOUND", "任务实例不存在");
  if (!Number.isFinite(Date.parse(occurredAt))) throw new ApiError(400, "INVALID_REQUEST", "occurredAt 无效");
  const staleTimeZone = Number(occurrence.time_zone_version) !== Number(occurrence.current_time_zone_version);
  const reviewReason = occurrence.task_status === "CANCELLED" || occurrence.assignment_status === "CANCELLED" ? "CANCELLED_AFTER_SEEN" : Number(occurrence.current_revision) !== taskRevision || staleTimeZone ? "STALE_REVISION" : null;
  const storedOccurrenceKey = String(occurrence.storage_occurrence_key);
  const selected = await env.DB.prepare("SELECT r.execution_event_id,r.selected_by_membership_id,e.event_type,e.occurred_at FROM result_selections r JOIN execution_events e ON e.id=r.execution_event_id WHERE r.space_id=? AND r.assignment_id=? AND r.occurrence_key=? ORDER BY r.created_at DESC,r.rowid DESC LIMIT 1")
    .bind(spaceId, assignmentId, storedOccurrenceKey).first<{ execution_event_id: string; selected_by_membership_id: string; event_type: string; occurred_at: string }>();
  const terminal = new Set(["COMPLETED", "RESULT_SUBMITTED", "CORRECTION"]).has(eventType);
  const undo = eventType === "COMPLETION_UNDONE";
  if (undo) {
    const data = strictObject(payload.data, ["status", "localOccurrenceKey", "taskName", "taskDate", "executionKind"], "data");
    if (!["PENDING", "NOT_STARTED", "MISSED"].includes(String(data.status))) throw new ApiError(400, "INVALID_REQUEST", "撤销完成后的状态无效");
  }
  const followsSelected = !selected || Date.parse(selected.occurred_at) <= Date.parse(occurredAt);
  // Preserve explicit administrator choices and do not let delayed old events
  // replace a newer result. A later completion can follow an accepted undo.
  const selectAutomatically = undo
    ? followsSelected && (!selected || selected.selected_by_membership_id === memberId)
    : terminal && (!selected || (selected.event_type === "COMPLETION_UNDONE" && selected.selected_by_membership_id === memberId && followsSelected));
  const duplicateOf = terminal && selected && !selectAutomatically ? selected.execution_event_id : null; const now = iso();
  const value = { id: command.entityId, assignmentId, occurrenceKey, taskRevision, eventType, data: payload.data ?? null, occurredAt, receivedAt: now, reviewReason, staleTimeZone, duplicateOf };
  const statements: D1PreparedStatement[] = [
    env.DB.prepare("INSERT INTO execution_events(id,space_id,assignment_id,executor_membership_id,task_revision,event_type,payload_json,review_reason,duplicate_of,occurred_at,received_at,occurrence_key,payload_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)")
      .bind(command.entityId, spaceId, assignmentId, memberId, taskRevision, eventType, payload.data === undefined ? null : JSON.stringify(payload.data), reviewReason, duplicateOf, occurredAt, now, storedOccurrenceKey),
    ...entityStatements(env, spaceId, "execution_event", command.entityId, 1, value, now),
  ];
  if (selectAutomatically) {
    const selectionId = uuidV7();
    const reason = undo ? "COMPLETION_UNDONE" : "FIRST_VALID_RESULT";
    statements.push(env.DB.prepare("INSERT INTO result_selections(id,space_id,assignment_id,execution_event_id,selected_by_membership_id,reason,created_at,occurrence_key) VALUES (?,?,?,?,?,?,?,?)")
      .bind(selectionId, spaceId, assignmentId, command.entityId, memberId, reason, now, storedOccurrenceKey));
    statements.push(...entityStatements(env, spaceId, "result_selection", selectionId, 1, { id: selectionId, assignmentId, occurrenceKey, executionEventId: command.entityId, reason, selectedAt: now }, now));
  }
  stage(env, statements);
  const note = await notification(env, spaceId, "ADMIN", reviewReason ? "RESULT_NEEDS_REVIEW" : undo ? "COMPLETION_UNDONE" : "RESULT_SUBMITTED", command.entityId, storageKey, { assignmentId, occurrenceKey, reviewReason, staleTimeZone, duplicateOf }, now);
  await audit(env, principal, memberId, spaceId, "EXECUTION_EVENT_CREATED", "execution_event", command.entityId, { eventType, reviewReason, duplicate: duplicateOf !== null }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId), details: { reviewReason, duplicateOf, notificationId: note?.id ?? null } };
}

async function executeInformationSubmission(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["assignmentId", "occurrenceKey", "taskRevision", "content", "submittedAt"], "payload");
  const assignmentId = stringField(payload, "assignmentId", 64);
  const occurrenceKey = stringField(payload, "occurrenceKey", 160);
  const taskRevision = integerField(payload, "taskRevision", 1);
  const submittedAt = stringField(payload, "submittedAt", 40);
  const content = stringField(payload, "content", 4_000).trim();
  if (!content || Array.from(content).length > 2_000) throw new ApiError(400, "INVALID_REQUEST", "告知正文必须为 1～2000 个字符");
  if (!Number.isFinite(Date.parse(submittedAt))) throw new ApiError(400, "INVALID_REQUEST", "submittedAt 无效");
  const storageKey = occurrenceStorageKey(assignmentId, occurrenceKey);
  const occurrence = await env.DB.prepare("SELECT o.task_revision FROM task_occurrences o JOIN assignments a ON a.id=o.assignment_id WHERE o.occurrence_key IN (?,?) AND o.space_id=? AND o.assignment_id=? AND a.executor_membership_id=? LIMIT 1")
    .bind(storageKey, occurrenceKey, spaceId, assignmentId, memberId).first<{ task_revision: number }>();
  if (!occurrence) throw new ApiError(404, "NOT_FOUND", "任务实例不存在");
  if (occurrence.task_revision !== taskRevision) throw new ApiError(409, "CONFLICT", "告知正文与任务版本不一致");
  const now = iso();
  const value = { id: command.entityId, assignmentId, occurrenceKey, taskRevision, content, submittedAt, receivedAt: now };
  stage(env, entityStatements(env, spaceId, "information_submission", command.entityId, 1, value, now));
  const note = await notification(env, spaceId, "ADMIN", "INFORMATION_SUBMITTED", command.entityId, storageKey, { assignmentId, occurrenceKey, taskRevision }, now);
  await audit(env, principal, memberId, spaceId, "INFORMATION_SUBMITTED", "information_submission", command.entityId, { taskRevision, codePoints: Array.from(content).length }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId), details: note ? { notificationId: note.id } : undefined };
}

async function executeSelection(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["assignmentId", "occurrenceKey", "executionEventId", "reason"], "payload");
  const assignmentId = stringField(payload, "assignmentId", 64); const occurrenceKey = stringField(payload, "occurrenceKey", 160); const executionEventId = stringField(payload, "executionEventId", 64); const reason = stringField(payload, "reason", 200);
  const storageKey = occurrenceStorageKey(assignmentId, occurrenceKey);
  const event = await env.DB.prepare("SELECT e.id,e.occurrence_key,a.executor_membership_id FROM execution_events e JOIN assignments a ON a.id=e.assignment_id WHERE e.id=? AND e.space_id=? AND e.assignment_id=? AND e.occurrence_key IN (?,?)").bind(executionEventId, spaceId, assignmentId, storageKey, occurrenceKey).first<{ id: string; occurrence_key: string; executor_membership_id: string }>();
  if (!event) throw new ApiError(404, "NOT_FOUND", "候选结果不存在");
  const now = iso(); const value = { id: command.entityId, assignmentId, occurrenceKey, executionEventId, reason, selectedAt: now };
  stage(env, [
    env.DB.prepare("INSERT INTO result_selections(id,space_id,assignment_id,execution_event_id,selected_by_membership_id,reason,created_at,occurrence_key) VALUES (?,?,?,?,?,?,?,?)")
      .bind(command.entityId, spaceId, assignmentId, executionEventId, memberId, reason, now, event.occurrence_key),
    ...entityStatements(env, spaceId, "result_selection", command.entityId, 1, value, now),
  ]);
  const note = await notification(env, spaceId, "EXECUTOR", "RESULT_SELECTION_CHANGED", command.entityId, storageKey, { assignmentId, occurrenceKey, executionEventId }, now, event.executor_membership_id);
  await audit(env, principal, memberId, spaceId, "RESULT_SELECTION_CHANGED", "result_selection", command.entityId, { occurrenceKey }, request, now);
  return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId), details: note ? { notificationId: note.id } : undefined };
}

async function executeNotificationRead(env: CommandEnv, memberId: string, spaceId: string, command: SyncCommand): Promise<CommandResult> {
  const payload = strictObject(command.payload, ["notificationIds"], "payload");
  if (!Array.isArray(payload.notificationIds) || payload.notificationIds.length < 1 || payload.notificationIds.length > 100 || payload.notificationIds.some((id) => typeof id !== "string")) throw new ApiError(400, "INVALID_REQUEST", "notificationIds 无效");
  const ids = payload.notificationIds as string[]; const now = iso();
  const owned = await env.DB.prepare(`SELECT id FROM notifications WHERE space_id=? AND recipient_membership_id=? AND id IN (${ids.map(() => "?").join(",")})`).bind(spaceId, memberId, ...ids).all<{ id: string }>();
  if (owned.results.length !== new Set(ids).size) throw new ApiError(403, "FORBIDDEN", "包含不属于当前成员的通知");
  stage(env, ids.map((id) => env.DB.prepare("INSERT INTO notification_reads(notification_id,membership_id,read_at) VALUES (?,?,?) ON CONFLICT(notification_id,membership_id) DO UPDATE SET read_at=excluded.read_at").bind(id, memberId, now)));
  const value = { membershipId: memberId, notificationIds: ids, readAt: now };
  stage(env, entityStatements(env, spaceId, "notification_read", command.commandId, 1, value, now));
  return { commandId: command.commandId, status: "accepted", entityVersion: 1, changeSequence: await currentSequence(env, spaceId) };
}

async function executeOne(env: CommandEnv, request: Request, principal: SessionPrincipal, memberId: string, role: "ADMIN" | "EXECUTOR", spaceId: string, command: SyncCommand): Promise<CommandResult> {
  if (["GROUP_UPSERT", "GROUP_ARCHIVE", "SPACE_TIMEZONE_UPDATE", "TASK_PUBLISH", "TASK_UPDATE", "TASK_CANCEL", "TASK_ARCHIVE", "TASK_RESTORE", "RESULT_SELECT"].includes(command.type) && role !== "ADMIN") throw new ApiError(403, "FORBIDDEN", "当前角色无权执行管理员命令");
  if (["OCCURRENCE_UPSERT", "EXECUTION_EVENT", "INFORMATION_SUBMISSION"].includes(command.type) && role !== "EXECUTOR") throw new ApiError(403, "FORBIDDEN", "当前角色无权执行任务命令");
  if (command.type === "GROUP_UPSERT" || command.type === "GROUP_ARCHIVE") return executeGroup(env, request, principal, memberId, spaceId, command);
  if (command.type === "SPACE_TIMEZONE_UPDATE") return executeTimezone(env, request, principal, memberId, spaceId, command);
  if (["TASK_PUBLISH", "TASK_UPDATE", "TASK_CANCEL", "TASK_ARCHIVE", "TASK_RESTORE"].includes(command.type)) return executeTask(env, request, principal, memberId, spaceId, command);
  if (command.type === "OCCURRENCE_UPSERT") return executeOccurrence(env, request, principal, memberId, spaceId, command);
  if (command.type === "EXECUTION_EVENT") return executeEvent(env, request, principal, memberId, spaceId, command);
  if (command.type === "INFORMATION_SUBMISSION") return executeInformationSubmission(env, request, principal, memberId, spaceId, command);
  if (command.type === "RESULT_SELECT") return executeSelection(env, request, principal, memberId, spaceId, command);
  return executeNotificationRead(env, memberId, spaceId, command);
}

function resultForError(commandId: string, error: unknown): CommandResult {
  if (!(error instanceof ApiError)) return { commandId, status: "retryable", code: "INTERNAL_ERROR" };
  if (error.code === "TASK_VERSION_CONFLICT" || error.code === "CONFLICT") return { commandId, status: "conflict", code: error.code };
  return { commandId, status: error.retryable || error.status >= 500 ? "retryable" : "rejected", code: error.code };
}

export async function bootstrap(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request);
  requireAccountReady(principal);
  const rows = await env.DB.prepare("SELECT m.id,m.role,m.space_id,m.status,s.name,s.time_zone,s.time_zone_version,s.current_sequence FROM memberships m JOIN spaces s ON s.id=m.space_id WHERE m.account_id=? AND m.status='ACTIVE' AND s.status='ACTIVE' ORDER BY m.joined_at")
    .bind(principal.accountId).all<MembershipRow>();
  return json(env, request, { account: { id: principal.accountId }, service: await serviceQuotaSnapshot(env), memberships: rows.results.map((row) => ({ id: row.id, role: row.role, status: row.status, space: { id: row.space_id, name: row.name, timeZone: row.time_zone, timeZoneVersion: row.time_zone_version, currentSequence: row.current_sequence } })) });
}

export async function submitCommands(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request, true);
  const member = await membership(env, principal, spaceId);
  const commands = parseCommands(await request.json());
  await recordUsage(env, "API_WRITE", commands.length);
  const results: CommandResult[] = [];
  for (const command of commands) {
    const hash = await sha256(stable(command));
    const prior = await env.DB.prepare("SELECT payload_hash,response_json FROM command_receipts WHERE command_id=? AND space_id=?").bind(command.commandId, spaceId).first<{ payload_hash: string; response_json: string }>();
    if (prior) {
      if (prior.payload_hash !== hash) {
        await env.DB.prepare("INSERT INTO sync_security_events(id,space_id,session_id,event_type,safe_metadata_json,occurred_at) VALUES (?,?,?,'IDEMPOTENCY_KEY_REUSED','{}',?)").bind(uuidV7(), spaceId, principal.sessionId, iso()).run();
        results.push({ commandId: command.commandId, status: "rejected", code: "IDEMPOTENCY_KEY_REUSED" });
      } else {
        const original = parseJson(prior.response_json) as unknown as CommandResult;
        results.push(replayReceipt(command.commandId, original));
      }
      continue;
    }
    let result: CommandResult;
    const commandEnv: CommandEnv = { ...env, writes: [] };
    const sequence = await currentSequence(env, spaceId);
    try { result = await executeOne(commandEnv, request, principal, member.id, member.role as "ADMIN" | "EXECUTOR", spaceId, command); }
    catch (error) { result = resultForError(command.commandId, error); }
    if (result.status !== "retryable") {
      try {
        const statements = result.status === "accepted" ? [
          // All reads used to plan this command precede the batch. Abort the entire
          // batch if another writer changed the space before it acquires the write lock.
          env.DB.prepare("INSERT INTO sync_write_guards(space_id,expected_sequence,actual_sequence) SELECT id,?,current_sequence FROM spaces WHERE id=?").bind(sequence, spaceId),
          ...commandEnv.writes,
        ] : [];
        statements.push(receiptStatement(env, principal, spaceId, hash, result));
        if (result.status === "accepted") statements.push(env.DB.prepare("DELETE FROM sync_write_guards WHERE space_id=?").bind(spaceId));
        statements.push(env.DB.prepare("SELECT response_json FROM command_receipts WHERE command_id=? AND space_id=?").bind(command.commandId, spaceId));
        const committed = await env.DB.batch<{ response_json: string }>(statements);
        result = parseJson(committed[committed.length - 1].results[0].response_json) as unknown as CommandResult;
      } catch (error) {
        // A concurrent delivery may already have committed this exact command.
        const committed = await env.DB.prepare("SELECT payload_hash,response_json FROM command_receipts WHERE command_id=? AND space_id=?").bind(command.commandId, spaceId).first<{ payload_hash: string; response_json: string }>();
        result = committed
          ? committed.payload_hash === hash
            ? replayReceipt(command.commandId, parseJson(committed.response_json) as unknown as CommandResult)
            : { commandId: command.commandId, status: "rejected", code: "IDEMPOTENCY_KEY_REUSED" }
          : resultForError(command.commandId, error);
      }
    }
    results.push(result);
  }
  return json(env, request, { results }, 207);
}

export async function changes(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request);
  const member = await membership(env, principal, spaceId);
  const url = new URL(request.url); const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 100), 1), 500);
  const raw = url.searchParams.get("cursor"); const cursor = raw ? await decodeCursor(env, raw, "changes", spaceId) : { kind: "changes" as const, v: 1 as const, spaceId, sequence: 0 };
  const highWatermark = await currentSequence(env, spaceId);
  const minimum = await env.DB.prepare("SELECT MIN(sequence) AS minimum FROM space_changes WHERE space_id=?").bind(spaceId).first<{ minimum: number | null }>();
  if (cursor.sequence > 0 && minimum && minimum.minimum !== null && cursor.sequence < minimum.minimum - 1) throw new ApiError(409, "SYNC_CURSOR_EXPIRED", "同步游标已过期，请重新获取快照");
  const rows = await env.DB.prepare("SELECT sequence,entity_type,entity_id,operation,entity_version,payload_version,payload_json,created_at FROM space_changes WHERE space_id=? AND sequence>? AND sequence<=? AND (entity_type NOT IN ('notification','notification_read') OR (entity_type='notification' AND json_extract(payload_json,'$.recipientMembershipId')=?) OR (entity_type='notification_read' AND json_extract(payload_json,'$.membershipId')=?)) AND (?='ADMIN' OR entity_type NOT IN ('task','assignment','occurrence','execution_event','information_submission','result_selection') OR (entity_type='task' AND entity_id IN (SELECT task_id FROM assignments WHERE space_id=? AND executor_membership_id=?)) OR json_extract(payload_json,'$.executorMembershipId')=? OR json_extract(payload_json,'$.assignmentId') IN (SELECT id FROM assignments WHERE space_id=? AND executor_membership_id=?)) ORDER BY sequence LIMIT ?")
    .bind(spaceId, cursor.sequence, highWatermark, member.id, member.id, member.role, spaceId, member.id, member.id, spaceId, member.id, limit + 1).all<Record<string, unknown>>();
  const page = rows.results.slice(0, limit); const hasMore = rows.results.length > limit; const sequence = hasMore && page.length ? Number(page[page.length - 1].sequence) : highWatermark;
  return json(env, request, { changes: page.map((row) => ({ sequence: row.sequence, entityType: row.entity_type, entityId: row.entity_id, operation: row.operation, entityVersion: row.entity_version, payloadVersion: row.payload_version, payload: row.payload_json ? parseJson(String(row.payload_json)) : null, createdAt: row.created_at })), nextCursor: await encodeCursor(env, { kind: "changes", v: 1, spaceId, sequence }), hasMore });
}

export async function snapshot(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request);
  const member = await membership(env, principal, spaceId);
  const url = new URL(request.url); const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 100), 1), 500); const raw = url.searchParams.get("cursor");
  const base = raw ? await decodeCursor(env, raw, "snapshot", spaceId) : { kind: "snapshot" as const, v: 1 as const, spaceId, baseSequence: await currentSequence(env, spaceId), entityType: "", entityId: "" };
  const rows = await env.DB.prepare("SELECT entity_type,entity_id,entity_version,payload_version,payload_json,updated_at FROM space_entities WHERE space_id=? AND change_sequence<=? AND (entity_type NOT IN ('notification','notification_read') OR (entity_type='notification' AND json_extract(payload_json,'$.recipientMembershipId')=?) OR (entity_type='notification_read' AND json_extract(payload_json,'$.membershipId')=?)) AND (?='ADMIN' OR entity_type NOT IN ('task','assignment','occurrence','execution_event','information_submission','result_selection') OR (entity_type='task' AND entity_id IN (SELECT task_id FROM assignments WHERE space_id=? AND executor_membership_id=?)) OR json_extract(payload_json,'$.executorMembershipId')=? OR json_extract(payload_json,'$.assignmentId') IN (SELECT id FROM assignments WHERE space_id=? AND executor_membership_id=?)) AND (entity_type>? OR (entity_type=? AND entity_id>?)) ORDER BY entity_type,entity_id LIMIT ?")
    .bind(spaceId, base.baseSequence, member.id, member.id, member.role, spaceId, member.id, member.id, spaceId, member.id, base.entityType, base.entityType, base.entityId, limit + 1).all<Record<string, unknown>>();
  const page = rows.results.slice(0, limit); const last = page.at(-1);
  const next = await encodeCursor(env, { kind: "snapshot", v: 1, spaceId, baseSequence: base.baseSequence, entityType: last ? String(last.entity_type) : base.entityType, entityId: last ? String(last.entity_id) : base.entityId });
  const changeCursor = await encodeCursor(env, { kind: "changes", v: 1, spaceId, sequence: base.baseSequence });
  return json(env, request, { entities: page.map((row) => ({ entityType: row.entity_type, entityId: row.entity_id, entityVersion: row.entity_version, payloadVersion: row.payload_version, payload: parseJson(String(row.payload_json)), updatedAt: row.updated_at })), nextCursor: next, hasMore: rows.results.length > limit, changesCursor: changeCursor });
}

export async function unreadNotifications(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request); const member = await membership(env, principal, spaceId);
  await recordUsage(env, "AUTO_SYNC_READ");
  const rows = await env.DB.prepare("SELECT n.id,n.type,n.entity_id,n.group_key,n.payload_json,n.created_at,r.read_at FROM notifications n LEFT JOIN notification_reads r ON r.notification_id=n.id AND r.membership_id=? WHERE n.space_id=? AND n.recipient_membership_id=? ORDER BY n.created_at DESC LIMIT 500")
    .bind(member.id, spaceId, member.id).all<Record<string, unknown>>();
  const visible = new Map<string, Record<string, unknown>>();
  for (const row of rows.results) {
    const key = String(row.group_key ?? row.id);
    const item = visible.get(key);
    if (!item) visible.set(key, { id: row.id, notificationIds: [row.id], groupKey: key, type: row.type, entityId: row.entity_id, payload: parseJson(String(row.payload_json)), createdAt: row.created_at, readAt: row.read_at, eventCount: 1 });
    else {
      item.eventCount = Number(item.eventCount) + 1;
      (item.notificationIds as unknown[]).push(row.id);
      if (!row.read_at) item.readAt = null;
    }
  }
  const notifications = [...visible.values()];
  return json(env, request, { notifications, unreadCount: notifications.filter((item) => !item.readAt).length });
}

export async function auditTimeline(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request);
  await membership(env, principal, spaceId, "ADMIN");
  const url = new URL(request.url);
  const before = url.searchParams.get("before") ?? "9999-12-31T23:59:59.999Z";
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 100), 1), 200);
  const rows = await env.DB.prepare("SELECT id,event_type,entity_type,entity_id,safe_metadata_json,occurred_at FROM audit_events WHERE space_id=? AND occurred_at<? ORDER BY occurred_at DESC,id DESC LIMIT ?")
    .bind(spaceId, before, limit + 1).all<Record<string, unknown>>();
  const page = rows.results.slice(0, limit);
  return json(env, request, {
    events: page.map((row) => ({ id: row.id, eventType: row.event_type, entityType: row.entity_type, entityId: row.entity_id, metadata: parseJson(String(row.safe_metadata_json)), occurredAt: row.occurred_at })),
    nextBefore: page.length ? page[page.length - 1].occurred_at : null,
    hasMore: rows.results.length > limit,
  });
}
