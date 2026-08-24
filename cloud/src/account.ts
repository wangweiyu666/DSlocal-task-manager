import { authenticate, CURRENT_PRIVACY_NOTICE_VERSION, requireAccountReady, requireFreshSensitiveVerification } from "./auth";
import { decodeCursor, encodeCursor } from "./cursor";
import { addSeconds, hmac, uuidV7 } from "./crypto";
import { ApiError, json, readObject, requestId } from "./http";
import { membership } from "./spaces";
import type { Env, SessionPrincipal } from "./types";

const DAY = 86_400;
const nowIso = () => new Date().toISOString();

export async function accountStatus(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request);
  const account = await env.DB.prepare("SELECT deletion_due_at FROM accounts WHERE id=?").bind(principal.accountId).first<{ deletion_due_at: string | null }>();
  return json(env, request, {
    account: {
      id: principal.accountId,
      status: principal.accountStatus,
      privacyNoticeVersion: principal.privacyNoticeVersion,
      requiredPrivacyNoticeVersion: CURRENT_PRIVACY_NOTICE_VERSION,
      deletionDueAt: account?.deletion_due_at ?? null,
    },
  });
}

export async function acknowledgePrivacy(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request, true);
  if (principal.accountStatus !== "ACTIVE") throw new ApiError(409, "ACCOUNT_DELETION_PENDING", "账号正在删除恢复期内");
  const body = await readObject(request, ["version"]);
  if (body.version !== CURRENT_PRIVACY_NOTICE_VERSION) throw new ApiError(409, "PRIVACY_NOTICE_CHANGED", "隐私说明已更新，请重新阅读");
  const now = nowIso();
  await env.DB.prepare("UPDATE accounts SET privacy_notice_version=?,privacy_acknowledged_at=? WHERE id=? AND status='ACTIVE'")
    .bind(CURRENT_PRIVACY_NOTICE_VERSION, now, principal.accountId).run();
  return json(env, request, { acknowledged: true, version: CURRENT_PRIVACY_NOTICE_VERSION, acknowledgedAt: now });
}

interface ExportDataset {
  type: string;
  query: (env: Env, principal: SessionPrincipal, memberId: string, spaceId: string, exportedAt: string, key: string, limit: number) => Promise<D1Result<Record<string, unknown>>>;
}

function rows(env: Env, sql: string, bindings: unknown[]): Promise<D1Result<Record<string, unknown>>> {
  return env.DB.prepare(sql).bind(...bindings).all<Record<string, unknown>>();
}

function exportRole(principal: SessionPrincipal): "ADMIN" | "EXECUTOR" {
  return (principal as SessionPrincipal & { exportRole: "ADMIN" | "EXECUTOR" }).exportRole;
}

const exportDatasets: ExportDataset[] = [
  {
    type: "space",
    query: (env, _principal, _memberId, spaceId, exportedAt, key, limit) => rows(env,
      "SELECT id AS export_key,json_object('id',id,'name',name,'status',status,'timeZone',time_zone,'timeZoneVersion',time_zone_version,'createdAt',created_at) AS export_json FROM spaces WHERE id=? AND created_at<=? AND id>? ORDER BY id LIMIT ?",
      [spaceId, exportedAt, key, limit]),
  },
  {
    type: "membership",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT m.id AS export_key,json_object('id',m.id,'role',m.role,'status',m.status,'joinedAt',m.joined_at,'removedAt',m.removed_at,'email',CASE WHEN m.status='ACTIVE' AND (?='ADMIN' OR m.id=?) THEN a.email_delivery ELSE NULL END) AS export_json
       FROM memberships m LEFT JOIN accounts a ON a.id=m.account_id
       WHERE m.space_id=? AND m.joined_at<=? AND m.id>? AND (?='ADMIN' OR m.id=?) ORDER BY m.id LIMIT ?`,
      [exportRole(principal), memberId, spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "taskGroup",
    query: (env, _principal, _memberId, spaceId, exportedAt, key, limit) => rows(env,
      "SELECT id AS export_key,json_object('id',id,'currentRevision',current_revision,'status',status,'createdAt',created_at,'updatedAt',updated_at) AS export_json FROM task_groups WHERE space_id=? AND created_at<=? AND id>? ORDER BY id LIMIT ?",
      [spaceId, exportedAt, key, limit]),
  },
  {
    type: "taskGroupRevision",
    query: (env, _principal, _memberId, spaceId, exportedAt, key, limit) => rows(env,
      "SELECT group_id||':'||printf('%010d',revision) AS export_key,json_object('groupId',group_id,'revision',revision,'content',json(content_json),'createdAt',created_at) AS export_json FROM task_group_revisions WHERE space_id=? AND created_at<=? AND group_id||':'||printf('%010d',revision)>? ORDER BY export_key LIMIT ?",
      [spaceId, exportedAt, key, limit]),
  },
  {
    type: "task",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT t.id AS export_key,json_object('id',t.id,'currentRevision',t.current_revision,'status',t.status,'assignmentMode',t.assignment_mode,'createdAt',t.created_at,'updatedAt',t.updated_at) AS export_json
       FROM tasks t WHERE t.space_id=? AND t.created_at<=? AND t.id>? AND (?='ADMIN' OR EXISTS(SELECT 1 FROM assignments a WHERE a.task_id=t.id AND a.space_id=t.space_id AND a.executor_membership_id=?)) ORDER BY t.id LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "taskRevision",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT r.task_id||':'||printf('%010d',r.revision) AS export_key,json_object('taskId',r.task_id,'revision',r.revision,'content',json(r.content_json),'assignmentMode',r.assignment_mode,'createdAt',r.created_at) AS export_json
       FROM task_revisions r WHERE r.space_id=? AND r.created_at<=? AND r.task_id||':'||printf('%010d',r.revision)>? AND (?='ADMIN' OR EXISTS(SELECT 1 FROM assignments a WHERE a.task_id=r.task_id AND a.space_id=r.space_id AND a.executor_membership_id=? AND (a.assigned_revision=r.revision OR EXISTS(SELECT 1 FROM execution_events e WHERE e.assignment_id=a.id AND e.task_revision=r.revision)))) ORDER BY export_key LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "assignment",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT id AS export_key,json_object('id',id,'taskId',task_id,'executorMembershipId',executor_membership_id,'assignedRevision',assigned_revision,'status',status,'createdAt',created_at,'updatedAt',updated_at) AS export_json FROM assignments WHERE space_id=? AND created_at<=? AND id>? AND (?='ADMIN' OR executor_membership_id=?) ORDER BY id LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "occurrence",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT o.occurrence_key AS export_key,json_object('occurrenceKey',o.occurrence_key,'assignmentId',o.assignment_id,'taskId',o.task_id,'taskRevision',o.task_revision,'timeZoneVersion',o.time_zone_version,'localDate',o.local_date,'scheduledAt',o.scheduled_at,'status',o.status,'createdAt',o.created_at,'updatedAt',o.updated_at) AS export_json FROM task_occurrences o JOIN assignments a ON a.id=o.assignment_id WHERE o.space_id=? AND o.created_at<=? AND o.occurrence_key>? AND (?='ADMIN' OR a.executor_membership_id=?) ORDER BY o.occurrence_key LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "executionEvent",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT e.id AS export_key,json_object('id',e.id,'assignmentId',e.assignment_id,'taskRevision',e.task_revision,'occurrenceKey',e.occurrence_key,'eventType',e.event_type,'payload',CASE WHEN e.payload_json IS NULL THEN NULL ELSE json(e.payload_json) END,'reviewReason',e.review_reason,'duplicateOf',e.duplicate_of,'occurredAt',e.occurred_at,'receivedAt',e.received_at) AS export_json FROM execution_events e JOIN assignments a ON a.id=e.assignment_id WHERE e.space_id=? AND e.received_at<=? AND e.id>? AND (?='ADMIN' OR a.executor_membership_id=?) ORDER BY e.id LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "resultSelection",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT r.id AS export_key,json_object('id',r.id,'assignmentId',r.assignment_id,'occurrenceKey',r.occurrence_key,'executionEventId',r.execution_event_id,'reason',r.reason,'createdAt',r.created_at) AS export_json FROM result_selections r JOIN assignments a ON a.id=r.assignment_id WHERE r.space_id=? AND r.created_at<=? AND r.id>? AND (?='ADMIN' OR a.executor_membership_id=?) ORDER BY r.id LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "notification",
    query: (env, principal, memberId, spaceId, exportedAt, key, limit) => rows(env,
      `SELECT n.id AS export_key,json_object('id',n.id,'recipientMembershipId',n.recipient_membership_id,'type',n.type,'entityId',n.entity_id,'payload',json(n.payload_json),'createdAt',n.created_at,'readAt',(SELECT r.read_at FROM notification_reads r WHERE r.notification_id=n.id AND r.membership_id=n.recipient_membership_id)) AS export_json FROM notifications n WHERE n.space_id=? AND n.created_at<=? AND n.id>? AND (?='ADMIN' OR n.recipient_membership_id=?) ORDER BY n.id LIMIT ?`,
      [spaceId, exportedAt, key, exportRole(principal), memberId, limit]),
  },
  {
    type: "invitation",
    query: (env, principal, _memberId, spaceId, exportedAt, key, limit) => exportRole(principal) !== "ADMIN"
      ? rows(env, "SELECT '' AS export_key,'{}' AS export_json WHERE 0", [])
      : rows(env,
        "SELECT id AS export_key,json_object('id',id,'status',status,'email',CASE WHEN status='ACTIVE' AND expires_at>? THEN email_normalized ELSE NULL END,'createdAt',created_at,'expiresAt',expires_at,'acceptedAt',accepted_at) AS export_json FROM invitations WHERE space_id=? AND created_at<=? AND id>? ORDER BY id LIMIT ?",
        [exportedAt, spaceId, exportedAt, key, limit]),
  },
  {
    type: "auditEvent",
    query: (env, principal, _memberId, spaceId, exportedAt, key, limit) => exportRole(principal) !== "ADMIN"
      ? rows(env, "SELECT '' AS export_key,'{}' AS export_json WHERE 0", [])
      : rows(env,
        "SELECT id AS export_key,json_object('id',id,'eventType',event_type,'entityType',entity_type,'entityId',entity_id,'metadata',json(safe_metadata_json),'occurredAt',occurred_at) AS export_json FROM audit_events WHERE space_id=? AND occurred_at<=? AND id>? ORDER BY id LIMIT ?",
        [spaceId, exportedAt, key, limit]),
  },
];

export async function exportData(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request);
  requireAccountReady(principal);
  requireFreshSensitiveVerification(principal);
  const member = await membership(env, principal, spaceId);
  const role = member.role as "ADMIN" | "EXECUTOR";
  const exportPrincipal = Object.assign(principal, { exportRole: role });
  const url = new URL(request.url);
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 100), 1), 200);
  const raw = url.searchParams.get("cursor");
  const cursor = raw
    ? await decodeCursor(env, raw, "export", spaceId)
    : { kind: "export" as const, v: 1 as const, spaceId, role, exportedAt: nowIso(), dataset: 0, key: "" };
  if (cursor.role !== role || cursor.dataset < 0 || cursor.dataset > exportDatasets.length) throw new ApiError(400, "INVALID_REQUEST", "导出游标无效");
  let dataset = cursor.dataset;
  let key = cursor.key;
  let result: D1Result<Record<string, unknown>> | null = null;
  while (dataset < exportDatasets.length) {
    result = await exportDatasets[dataset].query(env, exportPrincipal, member.id, spaceId, cursor.exportedAt, key, limit + 1);
    if (result.results.length) break;
    dataset += 1;
    key = "";
  }
  const records = (result?.results ?? []).slice(0, limit);
  const sameDatasetHasMore = (result?.results.length ?? 0) > limit;
  const nextDataset = sameDatasetHasMore ? dataset : dataset + 1;
  const nextKey = sameDatasetHasMore && records.length ? String(records[records.length - 1].export_key) : "";
  const hasMore = nextDataset < exportDatasets.length;
  const nextCursor = hasMore ? await encodeCursor(env, { kind: "export", v: 1, spaceId, role, exportedAt: cursor.exportedAt, dataset: nextDataset, key: nextKey }) : null;
  if (!raw) {
    await env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .bind(uuidV7(), spaceId, principal.accountId, member.id, "DATA_EXPORT_STARTED", "space", spaceId, JSON.stringify({ role, format: "DSEXPORT", version: 1 }), requestId(request), cursor.exportedAt).run();
  }
  return json(env, request, {
    manifest: raw ? null : { format: "DSEXPORT", version: 1, exportedAt: cursor.exportedAt, role, scope: role === "ADMIN" ? "SPACE" : "ACCOUNT_IN_SPACE", spaceId, accountId: principal.accountId },
    dataset: dataset < exportDatasets.length ? exportDatasets[dataset].type : null,
    records: records.map((row) => JSON.parse(String(row.export_json)) as unknown),
    nextCursor,
    hasMore,
  });
}

async function deleteSpaceData(env: Env, spaceId: string, now: string): Promise<void> {
  const statements = [
    env.DB.prepare("UPDATE device_sessions SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,'SPACE_DELETED') WHERE account_id IN (SELECT account_id FROM memberships WHERE space_id=? AND account_id IS NOT NULL)").bind(now, spaceId),
    env.DB.prepare("DELETE FROM notification_reads WHERE notification_id IN (SELECT id FROM notifications WHERE space_id=?)").bind(spaceId),
    env.DB.prepare("DELETE FROM notification_reads WHERE membership_id IN (SELECT id FROM memberships WHERE space_id=?)").bind(spaceId),
    env.DB.prepare("DELETE FROM notifications WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM result_selections WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM execution_events WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM task_occurrences WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM assignments WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM task_revisions WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM tasks WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM task_group_revisions WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM task_groups WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM space_changes WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM space_entities WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM command_receipts WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM sync_security_events WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM audit_events WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM invitations WHERE space_id=?").bind(spaceId),
    env.DB.prepare("UPDATE deletion_requests SET space_id=NULL WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM memberships WHERE space_id=?").bind(spaceId),
    env.DB.prepare("DELETE FROM spaces WHERE id=?").bind(spaceId),
  ];
  await env.DB.batch(statements);
}

async function anonymizeExecutor(env: Env, membershipId: string, spaceId: string, tombstoneId: string, now: string): Promise<void> {
  const events = await env.DB.prepare("SELECT id FROM execution_events WHERE space_id=? AND executor_membership_id=?").bind(spaceId, membershipId).all<{ id: string }>();
  const eventIds = events.results.map((item) => item.id);
  const statements: D1PreparedStatement[] = [
    env.DB.prepare("DELETE FROM notification_reads WHERE membership_id=?").bind(membershipId),
    env.DB.prepare("DELETE FROM notification_reads WHERE notification_id IN (SELECT id FROM notifications WHERE recipient_membership_id=?)").bind(membershipId),
    env.DB.prepare("DELETE FROM notifications WHERE recipient_membership_id=?").bind(membershipId),
    env.DB.prepare("UPDATE execution_events SET payload_json=NULL,event_type='DELETED',review_reason=NULL,duplicate_of=NULL,tombstone_id=? WHERE space_id=? AND executor_membership_id=?").bind(tombstoneId, spaceId, membershipId),
    env.DB.prepare("UPDATE audit_events SET actor_account_id=NULL,actor_membership_id=NULL WHERE actor_membership_id=?").bind(membershipId),
    env.DB.prepare("UPDATE memberships SET account_id=NULL,status='DELETED',removed_at=?,tombstone_id=? WHERE id=?").bind(now, tombstoneId, membershipId),
    env.DB.prepare("UPDATE space_entities SET entity_version=entity_version+1,payload_json=?,updated_at=? WHERE space_id=? AND entity_type='membership' AND entity_id=?")
      .bind(JSON.stringify({ id: membershipId, role: "EXECUTOR", status: "DELETED", tombstoneId, deletedAt: now }), now, spaceId, membershipId),
  ];
  for (const eventId of eventIds) {
    statements.push(
      env.DB.prepare("UPDATE space_entities SET entity_version=entity_version+1,payload_json=?,updated_at=? WHERE space_id=? AND entity_type IN ('execution_event','information_submission') AND entity_id=?")
        .bind(JSON.stringify({ id: eventId, status: "DELETED", tombstoneId, deletedAt: now }), now, spaceId, eventId),
      env.DB.prepare("UPDATE space_changes SET operation='TOMBSTONE',payload_json=? WHERE space_id=? AND entity_type IN ('execution_event','information_submission') AND entity_id=?")
        .bind(JSON.stringify({ id: eventId, status: "DELETED", tombstoneId, deletedAt: now }), spaceId, eventId),
    );
  }
  await env.DB.batch(statements);
}

async function purgeAccountData(env: Env, accountId: string, now: string): Promise<void> {
  const account = await env.DB.prepare("SELECT email_normalized,email_delivery FROM accounts WHERE id=?").bind(accountId).first<{ email_normalized: string; email_delivery: string }>();
  if (!account) return;
  const memberships = await env.DB.prepare("SELECT id,space_id,role FROM memberships WHERE account_id=? ORDER BY joined_at,id").bind(accountId).all<{ id: string; space_id: string; role: string }>();
  for (const item of memberships.results.filter((value) => value.role === "ADMIN")) await deleteSpaceData(env, item.space_id, now);
  for (const item of memberships.results.filter((value) => value.role === "EXECUTOR")) await anonymizeExecutor(env, item.id, item.space_id, uuidV7(), now);
  const invitationFingerprint = await hmac(env.AUTH_PEPPER, `email:${account.email_normalized}`);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM command_receipts WHERE session_id IN (SELECT id FROM device_sessions WHERE account_id=?)").bind(accountId),
    env.DB.prepare("DELETE FROM device_sessions WHERE account_id=?").bind(accountId),
    env.DB.prepare("DELETE FROM email_challenges WHERE email_normalized=?").bind(account.email_normalized),
    env.DB.prepare("DELETE FROM dev_mailbox WHERE recipient IN (?,?)").bind(account.email_normalized, account.email_delivery),
    env.DB.prepare("DELETE FROM invitations WHERE email_normalized=?").bind(account.email_normalized),
    env.DB.prepare("UPDATE audit_events SET safe_metadata_json=? WHERE safe_metadata_json LIKE ?").bind(JSON.stringify({ recipient: "deleted" }), `%${invitationFingerprint}%`),
    env.DB.prepare("UPDATE audit_events SET actor_account_id=NULL,actor_membership_id=NULL WHERE actor_account_id=?").bind(accountId),
    env.DB.prepare("UPDATE deletion_requests SET account_id=NULL,status='COMPLETED',completed_at=? WHERE account_id=?").bind(now, accountId),
    env.DB.prepare("DELETE FROM accounts WHERE id=?").bind(accountId),
  ]);
}

async function recordDeletion(env: Env, scope: "ACCOUNT" | "SPACE", targetId: string, now: string): Promise<string> {
  const id = uuidV7();
  await env.DELETION_LEDGER.prepare("INSERT INTO deletion_ledger(id,scope,target_id,created_at,expires_at) VALUES (?,?,?,?,?) ON CONFLICT(scope,target_id) DO UPDATE SET id=excluded.id,created_at=excluded.created_at,expires_at=excluded.expires_at")
    .bind(id, scope, targetId, now, addSeconds(now, 35 * DAY)).run();
  return id;
}

async function applyDeletion(env: Env, scope: "ACCOUNT" | "SPACE", targetId: string, createdAt: string, ledgerId: string): Promise<void> {
  if (scope === "ACCOUNT") await purgeAccountData(env, targetId, createdAt);
  else await deleteSpaceData(env, targetId, createdAt);
  await env.DB.prepare("UPDATE schema_metadata SET value=?,updated_at=? WHERE key='deletion_ledger_checkpoint'").bind(`${createdAt}|${ledgerId}`, createdAt).run();
}

export async function enforceDeletionLedger(env: Env): Promise<void> {
  const checkpoint = await env.DB.prepare("SELECT value FROM schema_metadata WHERE key='deletion_ledger_checkpoint'").first<{ value: string }>();
  const value = checkpoint?.value ?? "";
  const separator = value.lastIndexOf("|");
  const createdAt = separator >= 0 ? value.slice(0, separator) : "";
  const id = separator >= 0 ? value.slice(separator + 1) : "";
  const entries = await env.DELETION_LEDGER.prepare("SELECT id,scope,target_id,created_at FROM deletion_ledger WHERE created_at>? OR (created_at=? AND id>?) ORDER BY created_at,id LIMIT 100")
    .bind(createdAt, createdAt, id).all<{ id: string; scope: "ACCOUNT" | "SPACE"; target_id: string; created_at: string }>();
  for (const entry of entries.results) await applyDeletion(env, entry.scope, entry.target_id, entry.created_at, entry.id);
}

export async function requestDeletion(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request, true);
  requireAccountReady(principal);
  requireFreshSensitiveVerification(principal);
  const body = await readObject(request, ["mode"]);
  const mode = body.mode === undefined ? "SCHEDULED" : body.mode;
  if (mode !== "SCHEDULED" && mode !== "IMMEDIATE") throw new ApiError(400, "INVALID_REQUEST", "删除方式无效");
  const now = nowIso();
  const admin = await env.DB.prepare("SELECT m.space_id FROM memberships m WHERE m.account_id=? AND m.role='ADMIN' AND m.status='ACTIVE'").bind(principal.accountId).first<{ space_id: string }>();
  const requestRecordId = uuidV7();
  if (mode === "IMMEDIATE") {
    const ledgerId = await recordDeletion(env, "ACCOUNT", principal.accountId, now);
    await env.DB.prepare("INSERT INTO deletion_requests(id,account_id,space_id,scope,status,requested_at,execute_after) VALUES (?,?,?,'ACCOUNT','PENDING',?,?)")
      .bind(requestRecordId, principal.accountId, admin?.space_id ?? null, now, now).run();
    await applyDeletion(env, "ACCOUNT", principal.accountId, now, ledgerId);
    return json(env, request, { status: "COMPLETED", completedAt: now }, 200, { "Set-Cookie": "dst_refresh=; Path=/v1/auth; Max-Age=0; HttpOnly; Secure; SameSite=Strict" });
  }
  const executeAfter = addSeconds(now, 30 * DAY);
  const statements = [
    env.DB.prepare("UPDATE accounts SET status='DELETION_PENDING',deletion_due_at=? WHERE id=? AND status='ACTIVE'").bind(executeAfter, principal.accountId),
    env.DB.prepare("UPDATE device_sessions SET revoked_at=?,revoke_reason='ACCOUNT_DELETION_PENDING' WHERE account_id=? AND revoked_at IS NULL").bind(now, principal.accountId),
    env.DB.prepare("INSERT INTO deletion_requests(id,account_id,space_id,scope,status,requested_at,execute_after) VALUES (?,?,?,'ACCOUNT','PENDING',?,?)")
      .bind(requestRecordId, principal.accountId, admin?.space_id ?? null, now, executeAfter),
  ];
  if (admin) statements.push(
    env.DB.prepare("UPDATE spaces SET status='DELETION_PENDING',deletion_due_at=? WHERE id=?").bind(executeAfter, admin.space_id),
    env.DB.prepare("UPDATE device_sessions SET revoked_at=?,revoke_reason='SPACE_DELETION_PENDING' WHERE account_id IN (SELECT account_id FROM memberships WHERE space_id=? AND account_id IS NOT NULL) AND account_id<>? AND revoked_at IS NULL").bind(now, admin.space_id, principal.accountId),
  );
  await env.DB.batch(statements);
  return json(env, request, { status: "PENDING", executeAfter }, 200, { "Set-Cookie": "dst_refresh=; Path=/v1/auth; Max-Age=0; HttpOnly; Secure; SameSite=Strict" });
}

export async function cancelDeletion(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request, true);
  if (principal.accountStatus !== "DELETION_PENDING") throw new ApiError(409, "CONFLICT", "账号不在删除恢复期内");
  await readObject(request, []);
  const session = await env.DB.prepare("SELECT created_at FROM device_sessions WHERE id=?").bind(principal.sessionId).first<{ created_at: string }>();
  if (!session || Date.now() - Date.parse(session.created_at) > 600_000) throw new ApiError(403, "REAUTHENTICATION_REQUIRED", "请重新验证邮箱后恢复账号");
  const now = nowIso();
  await env.DB.batch([
    env.DB.prepare("UPDATE accounts SET status='ACTIVE',deletion_due_at=NULL WHERE id=? AND status='DELETION_PENDING'").bind(principal.accountId),
    env.DB.prepare("UPDATE spaces SET status='ACTIVE',deletion_due_at=NULL WHERE id IN (SELECT space_id FROM memberships WHERE account_id=? AND role='ADMIN') AND status='DELETION_PENDING'").bind(principal.accountId),
    env.DB.prepare("UPDATE deletion_requests SET status='CANCELLED',completed_at=? WHERE account_id=? AND status='PENDING'").bind(now, principal.accountId),
  ]);
  return json(env, request, { status: "ACTIVE", restoredAt: now });
}

export async function runMaintenance(env: Env): Promise<void> {
  await enforceDeletionLedger(env);
  const now = nowIso();
  const endedSessionCutoff = addSeconds(now, -30 * DAY);
  const due = await env.DB.prepare("SELECT account_id FROM deletion_requests WHERE status='PENDING' AND execute_after<=? AND account_id IS NOT NULL ORDER BY execute_after LIMIT 25")
    .bind(now).all<{ account_id: string }>();
  for (const item of due.results) {
    const ledgerId = await recordDeletion(env, "ACCOUNT", item.account_id, now);
    await applyDeletion(env, "ACCOUNT", item.account_id, now, ledgerId);
  }
  await env.DB.batch([
    env.DB.prepare("DELETE FROM email_challenges WHERE created_at<?").bind(addSeconds(now, -DAY)),
    env.DB.prepare("DELETE FROM command_receipts WHERE expires_at<? OR session_id IN (SELECT id FROM device_sessions WHERE (revoked_at IS NOT NULL AND revoked_at<?) OR absolute_expires_at<?)").bind(now, endedSessionCutoff, endedSessionCutoff),
    env.DB.prepare("DELETE FROM device_sessions WHERE (revoked_at IS NOT NULL AND revoked_at<?) OR absolute_expires_at<?").bind(endedSessionCutoff, endedSessionCutoff),
    env.DB.prepare("DELETE FROM space_changes WHERE expires_at<?").bind(now),
    env.DB.prepare("UPDATE invitations SET email_normalized='redacted+'||id||'@invalid.local' WHERE status!='ACTIVE' AND created_at<? AND email_normalized NOT LIKE 'redacted+%@invalid.local'").bind(addSeconds(now, -30 * DAY)),
    env.DB.prepare("DELETE FROM service_usage_daily WHERE usage_date<date(?, '-35 days')").bind(now),
  ]);
  await env.DELETION_LEDGER.prepare("DELETE FROM deletion_ledger WHERE expires_at<?").bind(now).run();
}
