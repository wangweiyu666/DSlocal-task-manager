import { addSeconds, hmac, uuidV7 } from "./crypto";
import { authenticate, normalizeEmail } from "./auth";
import { ApiError, json, readObject, requestId, requiredString } from "./http";
import { sendInvitation } from "./mail";
import type { Env, SessionPrincipal } from "./types";

function nowIso(): string { return new Date().toISOString(); }

async function invitationDigest(env: Env, token: string): Promise<string> {
  return hmac(env.AUTH_PEPPER, `invitation:${token}`);
}

function entityChangeStatements(env: Env, spaceId: string, entityType: string, entityId: string, version: number, payload: unknown, now: string): D1PreparedStatement[] {
  const payloadJson = JSON.stringify(payload);
  return [
    env.DB.prepare("UPDATE spaces SET current_sequence=current_sequence+1 WHERE id=?").bind(spaceId),
    env.DB.prepare("INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence) VALUES (?,?,?,?,1,?,?,(SELECT current_sequence FROM spaces WHERE id=?)) ON CONFLICT(space_id,entity_type,entity_id) DO UPDATE SET entity_version=excluded.entity_version,payload_version=excluded.payload_version,payload_json=excluded.payload_json,updated_at=excluded.updated_at,change_sequence=excluded.change_sequence")
      .bind(spaceId, entityType, entityId, version, payloadJson, now, spaceId),
    env.DB.prepare("INSERT INTO space_changes(space_id,sequence,entity_type,entity_id,operation,entity_version,payload_json,created_at,expires_at) SELECT id,current_sequence,?,?,'UPSERT',?,?,?,? FROM spaces WHERE id=?")
      .bind(entityType, entityId, version, payloadJson, now, addSeconds(now, 90 * 86_400), spaceId),
  ];
}

export async function membership(env: Env, principal: SessionPrincipal, spaceId: string, role?: "ADMIN" | "EXECUTOR"): Promise<{ id: string; role: string }> {
  const row = await env.DB.prepare("SELECT id,role FROM memberships WHERE space_id=? AND account_id=? AND status='ACTIVE'").bind(spaceId, principal.accountId).first<{ id: string; role: string }>();
  if (!row) throw new ApiError(403, "MEMBERSHIP_REVOKED", "你已不是该空间成员");
  if (role && row.role !== role) throw new ApiError(403, "FORBIDDEN", "当前角色无权执行此操作");
  const space = await env.DB.prepare("SELECT status FROM spaces WHERE id=?").bind(spaceId).first<{ status: string }>();
  if (!space) throw new ApiError(404, "NOT_FOUND", "空间不存在");
  if (space.status !== "ACTIVE") throw new ApiError(409, "SPACE_DELETION_PENDING", "空间正在删除流程中");
  return row;
}

export async function createSpace(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request, true);
  if (normalizeEmail(env.ADMIN_EMAIL) !== principal.email) throw new ApiError(403, "FORBIDDEN", "当前账号不能创建空间");
  const existing = await env.DB.prepare("SELECT 1 AS present FROM memberships WHERE account_id=? AND role='ADMIN' AND status='ACTIVE'").bind(principal.accountId).first();
  if (existing) throw new ApiError(409, "CONFLICT", "管理员已拥有空间");
  const body = await readObject(request, ["name"]);
  const name = requiredString(body, "name", 80).trim();
  if (!name) throw new ApiError(400, "INVALID_REQUEST", "空间名称不能为空");
  const now = nowIso();
  const spaceId = uuidV7();
  const membershipId = uuidV7();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO spaces(id,name,created_at) VALUES (?,?,?)").bind(spaceId, name, now),
    env.DB.prepare("INSERT INTO memberships(id,space_id,account_id,role,joined_at) VALUES (?,?,?,'ADMIN',?)").bind(membershipId, spaceId, principal.accountId, now),
    env.DB.prepare("INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence) VALUES (?,'space',?,1,1,?,?,0)")
      .bind(spaceId, spaceId, JSON.stringify({ id: spaceId, name, status: "ACTIVE", timeZone: "Asia/Hong_Kong", timeZoneVersion: 1 }), now),
    env.DB.prepare("INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence) VALUES (?,'membership',?,1,1,?,?,0)")
      .bind(spaceId, membershipId, JSON.stringify({ id: membershipId, role: "ADMIN", status: "ACTIVE", joinedAt: now }), now),
    env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .bind(uuidV7(), spaceId, principal.accountId, membershipId, "SPACE_CREATED", "space", spaceId, JSON.stringify({ nameLength: name.length }), requestId(request), now),
  ]);
  return json(env, request, { space: { id: spaceId, name, role: "ADMIN" } }, 201);
}

export async function createInvitation(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request, true);
  const admin = await membership(env, principal, spaceId, "ADMIN");
  const body = await readObject(request, ["email"]);
  const delivery = requiredString(body, "email");
  const email = normalizeEmail(delivery);
  if (email === principal.email) throw new ApiError(400, "INVALID_REQUEST", "管理员不能邀请自己成为执行者");
  const existingMember = await env.DB.prepare("SELECT m.id FROM memberships m JOIN accounts a ON a.id=m.account_id WHERE m.space_id=? AND m.status='ACTIVE' AND a.email_normalized=?").bind(spaceId, email).first();
  if (existingMember) throw new ApiError(409, "CONFLICT", "该邮箱已经是空间成员");
  const now = nowIso();
  const id = uuidV7();
  await env.DB.batch([
    env.DB.prepare("UPDATE invitations SET status='REVOKED' WHERE space_id=? AND email_normalized=? AND status='ACTIVE'").bind(spaceId, email),
    env.DB.prepare("INSERT INTO invitations(id,space_id,email_normalized,token_digest,created_by_membership_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?)")
      .bind(id, spaceId, email, await invitationDigest(env, id), admin.id, now, addSeconds(now, 259_200)),
    env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .bind(uuidV7(), spaceId, principal.accountId, admin.id, "INVITATION_CREATED", "invitation", id, JSON.stringify({ recipientFingerprint: await hmac(env.AUTH_PEPPER, `email:${email}`) }), requestId(request), now),
  ]);
  try { await sendInvitation(env, delivery, `invitation-${id}`); }
  catch (error) { await env.DB.prepare("UPDATE invitations SET status='REVOKED' WHERE id=?").bind(id).run(); throw error; }
  return json(env, request, { invitation: { id, expiresAt: addSeconds(now, 259_200) } }, 201);
}

export async function listInvitations(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request);
  const now = nowIso();
  const rows = await env.DB.prepare("SELECT i.id,i.space_id,s.name AS space_name,i.created_at,i.expires_at FROM invitations i JOIN spaces s ON s.id=i.space_id WHERE i.email_normalized=? AND i.status='ACTIVE' AND i.expires_at>? AND s.status='ACTIVE' ORDER BY i.created_at,i.id")
    .bind(principal.email, now).all<{ id: string; space_id: string; space_name: string; created_at: string; expires_at: string }>();
  return json(env, request, { invitations: rows.results.map((row) => ({ id: row.id, spaceId: row.space_id, spaceName: row.space_name, createdAt: row.created_at, expiresAt: row.expires_at })) });
}

async function acceptInvitationRecord(env: Env, request: Request, principal: SessionPrincipal, invitation: Record<string, unknown> | null): Promise<Response> {
  const now = nowIso();
  if (!invitation || invitation.status !== "ACTIVE" || String(invitation.expires_at) <= now || invitation.email_normalized !== principal.email) {
    throw new ApiError(400, "INVITATION_INVALID", "邀请无效或已过期");
  }
  const existingMember = await env.DB.prepare("SELECT m.id,m.status,COALESCE(e.entity_version,0) AS entity_version FROM memberships m LEFT JOIN space_entities e ON e.space_id=m.space_id AND e.entity_type='membership' AND e.entity_id=m.id WHERE m.space_id=? AND m.account_id=?")
    .bind(invitation.space_id, principal.accountId).first<{ id: string; status: string; entity_version: number }>();
  if (existingMember?.status === "ACTIVE") throw new ApiError(409, "CONFLICT", "当前账号已经加入该空间");
  const memberId = existingMember?.id ?? uuidV7();
  const memberVersion = Number(existingMember?.entity_version ?? 0) + 1;
  const activeTasks = await env.DB.prepare("SELECT id,current_revision FROM tasks WHERE space_id=? AND status='ACTIVE' AND assignment_mode='ALL'").bind(invitation.space_id).all<{ id: string; current_revision: number }>();
  const priorAssignments = await env.DB.prepare("SELECT id,task_id,assigned_revision,status FROM assignments WHERE space_id=? AND executor_membership_id=?")
    .bind(invitation.space_id, memberId).all<{ id: string; task_id: string; assigned_revision: number; status: string }>();
  const priorByTask = new Map(priorAssignments.results.map((item) => [item.task_id, item]));
  const allTaskIds = new Set(activeTasks.results.map((task) => task.id));
  const assignmentStatements = activeTasks.results.flatMap((task) => {
    const prior = priorByTask.get(task.id);
    const id = prior?.id ?? uuidV7();
    const payload = { id, taskId: task.id, executorMembershipId: memberId, assignedRevision: task.current_revision, status: "ACTIVE" };
    return [
      prior
        ? env.DB.prepare("UPDATE assignments SET assigned_revision=?,status='ACTIVE',updated_at=? WHERE id=?").bind(task.current_revision, now, id)
        : env.DB.prepare("INSERT INTO assignments(id,space_id,task_id,executor_membership_id,assigned_revision,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)").bind(id, invitation.space_id, task.id, memberId, task.current_revision, now, now),
      ...entityChangeStatements(env, String(invitation.space_id), "assignment", id, task.current_revision, payload, now),
    ];
  });
  const cancelledAssignments = priorAssignments.results.filter((item) => !allTaskIds.has(item.task_id) && item.status === "ACTIVE").flatMap((item) => {
    const payload = { id: item.id, taskId: item.task_id, executorMembershipId: memberId, assignedRevision: item.assigned_revision, status: "CANCELLED" };
    return [
      env.DB.prepare("UPDATE assignments SET status='CANCELLED',updated_at=? WHERE id=?").bind(now, item.id),
      ...entityChangeStatements(env, String(invitation.space_id), "assignment", item.id, item.assigned_revision, payload, now),
    ];
  });
  const membershipPayload = { id: memberId, role: "EXECUTOR", status: "ACTIVE", joinedAt: now };
  await env.DB.batch([
    existingMember
      ? env.DB.prepare("UPDATE memberships SET role='EXECUTOR',status='ACTIVE',joined_at=?,removed_at=NULL,tombstone_id=NULL WHERE id=?").bind(now, memberId)
      : env.DB.prepare("INSERT INTO memberships(id,space_id,account_id,role,joined_at) VALUES (?,?,?,'EXECUTOR',?)").bind(memberId, invitation.space_id, principal.accountId, now),
    env.DB.prepare("UPDATE invitations SET status='ACCEPTED',accepted_at=? WHERE id=? AND status='ACTIVE'").bind(now, invitation.id),
    ...entityChangeStatements(env, String(invitation.space_id), "membership", memberId, memberVersion, membershipPayload, now),
    ...assignmentStatements,
    ...cancelledAssignments,
    env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .bind(uuidV7(), invitation.space_id, principal.accountId, memberId, existingMember ? "MEMBER_REJOINED" : "INVITATION_ACCEPTED", "membership", memberId, "{}", requestId(request), now),
  ]);
  return json(env, request, { membership: { id: memberId, spaceId: invitation.space_id, role: "EXECUTOR" } }, 201);
}

export async function acceptInvitationById(env: Env, request: Request, invitationId: string): Promise<Response> {
  const principal = await authenticate(env, request, true);
  await readObject(request, []);
  const invitation = await env.DB.prepare("SELECT * FROM invitations WHERE id=? AND email_normalized=?").bind(invitationId, principal.email).first<Record<string, unknown>>();
  return acceptInvitationRecord(env, request, principal, invitation);
}

export async function listMembers(env: Env, request: Request, spaceId: string): Promise<Response> {
  const principal = await authenticate(env, request);
  await membership(env, principal, spaceId, "ADMIN");
  const rows = await env.DB.prepare("SELECT m.id,m.role,m.status,m.joined_at,a.email_delivery FROM memberships m LEFT JOIN accounts a ON a.id=m.account_id WHERE m.space_id=? AND m.status='ACTIVE' ORDER BY CASE m.role WHEN 'ADMIN' THEN 0 ELSE 1 END,m.joined_at,m.id")
    .bind(spaceId).all<{ id: string; role: string; status: string; joined_at: string; email_delivery: string | null }>();
  return json(env, request, { members: rows.results.map((row) => ({ id: row.id, role: row.role, status: row.status, joinedAt: row.joined_at, email: row.email_delivery })) });
}

export async function removeMember(env: Env, request: Request, spaceId: string, memberId: string): Promise<Response> {
  const principal = await authenticate(env, request, true);
  const admin = await membership(env, principal, spaceId, "ADMIN");
  const target = await env.DB.prepare("SELECT account_id,role,status FROM memberships WHERE id=? AND space_id=?").bind(memberId, spaceId).first<{ account_id: string; role: string; status: string }>();
  if (!target) throw new ApiError(404, "NOT_FOUND", "成员不存在");
  if (target.role === "ADMIN") throw new ApiError(409, "CONFLICT", "首版不能移除唯一管理员");
  if (target.status !== "ACTIVE") return json(env, request, { ok: true });
  const now = nowIso();
  await env.DB.batch([
    env.DB.prepare("UPDATE memberships SET status='REMOVED',removed_at=? WHERE id=?").bind(now, memberId),
    env.DB.prepare("UPDATE device_sessions SET revoked_at=?,revoke_reason='MEMBERSHIP_REMOVED' WHERE account_id=? AND revoked_at IS NULL").bind(now, target.account_id),
    env.DB.prepare("UPDATE spaces SET current_sequence=current_sequence+1 WHERE id=?").bind(spaceId),
    env.DB.prepare("INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence) VALUES (?,'membership',?,2,1,?,?,(SELECT current_sequence FROM spaces WHERE id=?)) ON CONFLICT(space_id,entity_type,entity_id) DO UPDATE SET entity_version=2,payload_json=excluded.payload_json,updated_at=excluded.updated_at,change_sequence=excluded.change_sequence")
      .bind(spaceId, memberId, JSON.stringify({ id: memberId, role: target.role, status: "REMOVED", removedAt: now }), now, spaceId),
    env.DB.prepare("INSERT INTO space_changes(space_id,sequence,entity_type,entity_id,operation,entity_version,payload_json,created_at,expires_at) SELECT id,current_sequence,'membership',?,'TOMBSTONE',2,?,?,datetime(?,'+90 days') FROM spaces WHERE id=?")
      .bind(memberId, JSON.stringify({ id: memberId, role: target.role, status: "REMOVED", removedAt: now }), now, now, spaceId),
    env.DB.prepare("INSERT INTO audit_events(id,space_id,actor_account_id,actor_membership_id,event_type,entity_type,entity_id,safe_metadata_json,request_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .bind(uuidV7(), spaceId, principal.accountId, admin.id, "MEMBER_REMOVED", "membership", memberId, "{}", requestId(request), now),
  ]);
  return json(env, request, { ok: true });
}
