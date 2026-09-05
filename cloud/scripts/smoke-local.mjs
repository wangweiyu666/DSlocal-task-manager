import { randomBytes, randomUUID } from "node:crypto";

const base = process.env.CLOUD_SMOKE_BASE ?? "http://127.0.0.1:8787";
const smokeAdminEmail = process.env.CLOUD_SMOKE_ADMIN_EMAIL ?? "admin@example.com";

async function response(path, init) {
  const value = await fetch(`${base}${path}`, init);
  const body = await value.json();
  return { response: value, body };
}

async function request(path, init) {
  const value = await response(path, init);
  if (!value.response.ok) throw new Error(`${init?.method ?? "GET"} ${path}: ${value.response.status} ${JSON.stringify(value.body)}`);
  return value.body;
}

async function expectError(path, status, code, init) {
  const value = await response(path, init);
  if (value.response.status !== status || value.body?.error?.code !== code) {
    throw new Error(`${init?.method ?? "GET"} ${path}: expected ${status}/${code}, got ${value.response.status} ${JSON.stringify(value.body)}`);
  }
}

async function collectExport(accessToken, spaceId) {
  const datasets = {};
  let manifest = null;
  let cursor;
  let pages = 0;
  do {
    const page = await request(`/v1/spaces/${spaceId}/export?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`, { headers: headers(accessToken) });
    manifest ??= page.manifest;
    if (page.dataset) (datasets[page.dataset] ??= []).push(...page.records);
    cursor = page.nextCursor ?? undefined;
    pages += 1;
    if (pages > 100) throw new Error("DSEXPORT pagination did not terminate");
  } while (cursor);
  return { manifest, datasets };
}

const headers = (token) => ({ Authorization: `Bearer ${token}`, "Content-Type": "application/json" });
const dstId = () => randomBytes(12).toString("base64url");
const smokeClientIp = () => {
  const value = randomBytes(8).toString("hex").match(/.{1,4}/g).join(":");
  return `2001:db8:${value}`;
};
const command = (type, entityId, payload, baseVersion = 0, commandId = randomUUID()) => ({
  commandId,
  entityId,
  baseVersion,
  createdAt: new Date().toISOString(),
  type,
  payload,
});

async function authenticate(email, acknowledgePrivacy = true) {
  const challenge = await request("/v1/auth/challenges", {
    method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": smokeClientIp() }, body: JSON.stringify({ email })
  });
  const mailbox = await request(`/__dev/mailbox?recipient=${encodeURIComponent(email)}`);
  const code = mailbox.messages.find((message) => message.id === `challenge-${challenge.challengeId}`)?.secret;
  if (!code) throw new Error(`missing local challenge mail for ${email}`);
  const session = await request("/v1/auth/verify", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ challengeId: challenge.challengeId, email, code })
  });
  const status = await request("/v1/account", { headers: headers(session.accessToken) });
  if (acknowledgePrivacy && status.account.status === "ACTIVE" && status.account.privacyNoticeVersion < status.account.requiredPrivacyNoticeVersion) {
    await request("/v1/account/privacy-acknowledgements", {
      method: "POST", headers: headers(session.accessToken), body: JSON.stringify({ version: status.account.requiredPrivacyNoticeVersion })
    });
  }
  return session;
}

const health = await request("/health");
if (health.environment !== "local" || health.schemaVersion !== "7") throw new Error(`unexpected health response: ${JSON.stringify(health)}`);

const privacyEmail = `privacy-${randomUUID()}@example.com`;
const privacySession = await authenticate(privacyEmail, false);
await expectError("/v1/bootstrap", 428, "PRIVACY_ACK_REQUIRED", { headers: headers(privacySession.accessToken) });
const privacyStatus = await request("/v1/account", { headers: headers(privacySession.accessToken) });
await request("/v1/account/privacy-acknowledgements", {
  method: "POST", headers: headers(privacySession.accessToken), body: JSON.stringify({ version: privacyStatus.account.requiredPrivacyNoticeVersion })
});

const admin = await authenticate(smokeAdminEmail);
let adminBootstrap = await request("/v1/bootstrap", { headers: headers(admin.accessToken) });
if (adminBootstrap.memberships.length === 0) {
  await request("/v1/spaces", {
    method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ name: "阶段 2 本地验收" })
  });
  adminBootstrap = await request("/v1/bootstrap", { headers: headers(admin.accessToken) });
}
const spaceId = adminBootstrap.memberships[0]?.space?.id;
if (adminBootstrap.memberships[0]?.role !== "ADMIN") throw new Error("admin bootstrap role missing");
await expectError(`/v1/spaces/${randomUUID()}/snapshot`, 403, "MEMBERSHIP_REVOKED", { headers: headers(admin.accessToken) });
const adminExport = await request(`/v1/spaces/${spaceId}/export?limit=5`, { headers: headers(admin.accessToken) });
if (adminExport.manifest?.format !== "DSEXPORT" || adminExport.manifest?.role !== "ADMIN") throw new Error("admin DSEXPORT manifest missing");

const executorEmail = `executor-${randomUUID()}@example.com`;
const invitation = await request(`/v1/spaces/${spaceId}/invitations`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ email: executorEmail })
});
const executor = await authenticate(executorEmail);
const pendingInvitation = await request("/v1/invitations", { headers: headers(executor.accessToken) });
if (!pendingInvitation.invitations.some((item) => item.id === invitation.invitation.id)) throw new Error("account invitation inbox is missing the invitation");
const pendingOnNextBootstrap = await request("/v1/invitations", { headers: headers(executor.accessToken) });
if (!pendingOnNextBootstrap.invitations.some((item) => item.id === invitation.invitation.id)) throw new Error("unaccepted invitation did not remain in the account inbox");
const accepted = await request(`/v1/invitations/${invitation.invitation.id}/accept`, {
  method: "POST", headers: headers(executor.accessToken), body: JSON.stringify({})
});
if ((await request("/v1/invitations", { headers: headers(executor.accessToken) })).invitations.length !== 0) throw new Error("accepted invitation remained in the inbox");

const secondExecutorEmail = `executor-${randomUUID()}@example.com`;
const secondInvitation = await request(`/v1/spaces/${spaceId}/invitations`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ email: secondExecutorEmail })
});
const secondExecutor = await authenticate(secondExecutorEmail);
const secondPending = await request("/v1/invitations", { headers: headers(secondExecutor.accessToken) });
if (!secondPending.invitations.some((item) => item.id === secondInvitation.invitation.id)) throw new Error("second account invitation inbox is incomplete");
const secondAccepted = await request(`/v1/invitations/${secondInvitation.invitation.id}/accept`, {
  method: "POST", headers: headers(secondExecutor.accessToken), body: JSON.stringify({})
});
const members = await request(`/v1/spaces/${spaceId}/members`, { headers: headers(admin.accessToken) });
if (!members.members.some((member) => member.id === accepted.membership.id && member.email === executorEmail)
  || !members.members.some((member) => member.id === secondAccepted.membership.id && member.email === secondExecutorEmail)) {
  throw new Error(`multi-executor member list incomplete: ${JSON.stringify(members)}`);
}
await expectError(`/v1/spaces/${spaceId}/members`, 403, "FORBIDDEN", { headers: headers(executor.accessToken) });

const beforePublish = await request(`/v1/spaces/${spaceId}/snapshot?limit=1`, { headers: headers(executor.accessToken) });
const taskId = dstId();
const publish = command("TASK_PUBLISH", taskId, { content: {
  v: 1,
  b: "CloudSmoke000001",
  t: [{ i: taskId, n: "每日巡检", r: 1, x: { f: 1, s: "2026-08-17" } }],
}, assignmentMode: "SELECTED", executorMembershipIds: [accepted.membership.id] });
const publishBatch = { commands: [publish] };
const published = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify(publishBatch)
});
if (published.results[0]?.status !== "accepted" || !Array.isArray(published.results[0]?.details?.assignments)) throw new Error(`task publish failed: ${JSON.stringify(published)}`);
const assignmentId = published.results[0].details.assignments.find((item) => item.executorMembershipId === accepted.membership.id)?.id;
if (!assignmentId || published.results[0].details.assignments.some((item) => item.executorMembershipId === secondAccepted.membership.id)) throw new Error(`selected assignment targeting failed: ${JSON.stringify(published)}`);
const publishedChanges = await request(`/v1/spaces/${spaceId}/changes?cursor=${encodeURIComponent(beforePublish.changesCursor)}&limit=20`, { headers: headers(executor.accessToken) });
const publishedTypes = new Set(publishedChanges.changes.map((change) => change.entityType));
for (const required of ["task", "assignment", "notification"]) if (!publishedTypes.has(required)) throw new Error(`publish change feed missing ${required}: ${JSON.stringify(publishedChanges)}`);
if (publishedChanges.changes.some((change) => change.entityType === "assignment" && change.payload.executorMembershipId !== accepted.membership.id)) throw new Error("first executor received another member's assignment");
const secondUnassignedSnapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=500`, { headers: headers(secondExecutor.accessToken) });
if (secondUnassignedSnapshot.entities.some((item) => item.entityType === "task" && item.entityId === taskId)
  || secondUnassignedSnapshot.entities.some((item) => item.entityType === "assignment" && item.payload.taskId === taskId)) throw new Error("unselected executor received the task");

const duplicate = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify(publishBatch)
});
if (duplicate.results[0]?.status !== "duplicate") throw new Error(`idempotency replay failed: ${JSON.stringify(duplicate)}`);
const reused = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ commands: [{ ...publish, payload: { content: { ...publish.payload.content, b: "CloudSmokeChanged" } } }] })
});
if (reused.results[0]?.code !== "IDEMPOTENCY_KEY_REUSED") throw new Error(`idempotency reuse was not rejected: ${JSON.stringify(reused)}`);

const update = command("TASK_UPDATE", taskId, {
  content: publish.payload.content,
  assignmentMode: "SELECTED",
  executorMembershipIds: [accepted.membership.id, secondAccepted.membership.id],
}, 1);
const updated = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ commands: [update] })
});
const secondAssignmentId = updated.results[0]?.details?.assignments?.find((item) => item.executorMembershipId === secondAccepted.membership.id)?.id;
if (updated.results[0]?.status !== "accepted" || !secondAssignmentId || updated.results[0].details.assignments.length !== 2) throw new Error(`assignment target update failed: ${JSON.stringify(updated)}`);
const secondAssignedSnapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=500`, { headers: headers(secondExecutor.accessToken) });
if (!secondAssignedSnapshot.entities.some((item) => item.entityType === "task" && item.entityId === taskId)
  || !secondAssignedSnapshot.entities.some((item) => item.entityType === "assignment" && item.entityId === secondAssignmentId)
  || secondAssignedSnapshot.entities.some((item) => item.entityType === "assignment" && item.entityId === assignmentId)) throw new Error("selected executor task visibility is incorrect after update");

const snapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=2`, { headers: headers(executor.accessToken) });
if (!snapshot.entities.length || !snapshot.nextCursor || !snapshot.changesCursor) throw new Error("snapshot contract incomplete");
const changePage = await request(`/v1/spaces/${spaceId}/changes?cursor=${encodeURIComponent(snapshot.changesCursor)}`, { headers: headers(executor.accessToken) });
if (!Array.isArray(changePage.changes)) throw new Error("change feed contract incomplete");
const cursorLastCharacter = snapshot.changesCursor.at(-1);
const tamperedCursor = `${snapshot.changesCursor.slice(0, -1)}${cursorLastCharacter === "0" ? "1" : "0"}`;
await expectError(`/v1/spaces/${spaceId}/changes?cursor=${encodeURIComponent(tamperedCursor)}`, 400, "INVALID_REQUEST", { headers: headers(executor.accessToken) });

const occurrenceKey = `${taskId}:2:1:2026-08-17T09:30`;
const occurrence = command("OCCURRENCE_UPSERT", occurrenceKey, {
  taskId,
  assignmentId,
  occurrenceKey,
  localDate: "2026-08-17",
  scheduledAt: "2026-08-17T01:30:00Z",
  taskRevision: 2,
  timeZoneVersion: 1,
});
const generated = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(executor.accessToken), body: JSON.stringify({ commands: [occurrence] })
});
if (generated.results[0]?.status !== "accepted") throw new Error(`occurrence upload failed: ${JSON.stringify(generated)}`);

const eventId = randomUUID();
const result = command("EXECUTION_EVENT", eventId, {
  assignmentId,
  occurrenceKey,
  taskRevision: 2,
  eventType: "RESULT_SUBMITTED",
  data: { completed: true },
  occurredAt: "2026-08-17T01:35:00Z",
});
const submitted = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(executor.accessToken), body: JSON.stringify({ commands: [result] })
});
if (submitted.results[0]?.status !== "accepted" || submitted.results[0]?.details?.reviewReason !== null) throw new Error(`result upload failed: ${JSON.stringify(submitted)}`);

const secondOccurrence = command("OCCURRENCE_UPSERT", occurrenceKey, {
  taskId,
  assignmentId: secondAssignmentId,
  occurrenceKey,
  localDate: "2026-08-17",
  scheduledAt: "2026-08-17T01:30:00Z",
  taskRevision: 2,
  timeZoneVersion: 1,
});
const secondGenerated = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(secondExecutor.accessToken), body: JSON.stringify({ commands: [secondOccurrence] })
});
if (secondGenerated.results[0]?.status !== "accepted") throw new Error(`second occurrence upload failed: ${JSON.stringify(secondGenerated)}`);

const secondEventId = randomUUID();
const secondResult = command("EXECUTION_EVENT", secondEventId, {
  assignmentId: secondAssignmentId,
  occurrenceKey,
  taskRevision: 2,
  eventType: "RESULT_SUBMITTED",
  data: { completed: false },
  occurredAt: "2026-08-17T01:36:00Z",
});
const secondSubmitted = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(secondExecutor.accessToken), body: JSON.stringify({ commands: [secondResult] })
});
if (secondSubmitted.results[0]?.status !== "accepted" || secondSubmitted.results[0]?.details?.duplicateOf !== null) throw new Error(`second executor result was not isolated: ${JSON.stringify(secondSubmitted)}`);
const secondResultSnapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=500`, { headers: headers(secondExecutor.accessToken) });
if (!secondResultSnapshot.entities.some((item) => item.entityType === "execution_event" && item.entityId === secondEventId)
  || secondResultSnapshot.entities.some((item) => item.entityType === "execution_event" && item.entityId === eventId)) throw new Error("second executor result visibility is incorrect");
const adminResultSnapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=500`, { headers: headers(admin.accessToken) });
if (!adminResultSnapshot.entities.some((item) => item.entityType === "execution_event" && item.entityId === eventId)
  || !adminResultSnapshot.entities.some((item) => item.entityType === "execution_event" && item.entityId === secondEventId)) throw new Error("admin cannot see both executor results");
const notifications = await request(`/v1/spaces/${spaceId}/notifications`, { headers: headers(admin.accessToken) });
if (notifications.unreadCount < 1) throw new Error("admin result notification missing");

const executorExport = await collectExport(executor.accessToken, spaceId);
if (executorExport.manifest?.role !== "EXECUTOR") throw new Error("executor DSEXPORT manifest missing");
if (executorExport.datasets.invitation?.length || executorExport.datasets.auditEvent?.length) throw new Error("executor export leaked admin-only datasets");
if (!executorExport.datasets.task?.some((item) => item.id === taskId)) throw new Error("executor export omitted an assigned task");
if (executorExport.datasets.membership?.some((item) => item.id !== accepted.membership.id)
  || executorExport.datasets.assignment?.some((item) => item.executorMembershipId !== accepted.membership.id)) throw new Error("executor export crossed member scope");

const secondNotificationCount = (await request(`/v1/spaces/${spaceId}/notifications`, { headers: headers(secondExecutor.accessToken) })).notifications
  .reduce((total, item) => total + Number(item.eventCount ?? 1), 0);
const deselect = command("TASK_UPDATE", taskId, {
  content: publish.payload.content,
  assignmentMode: "SELECTED",
  executorMembershipIds: [accepted.membership.id],
}, 2);
const deselected = await request(`/v1/spaces/${spaceId}/commands`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ commands: [deselect] })
});
if (deselected.results[0]?.status !== "accepted" || deselected.results[0]?.details?.assignments?.length !== 1) throw new Error(`assignment deselection failed: ${JSON.stringify(deselected)}`);
const secondDeselectedSnapshot = await request(`/v1/spaces/${spaceId}/snapshot?limit=500`, { headers: headers(secondExecutor.accessToken) });
if (!secondDeselectedSnapshot.entities.some((item) => item.entityType === "assignment" && item.entityId === secondAssignmentId && item.payload.status === "CANCELLED")) throw new Error("deselected executor did not receive assignment cancellation");
const secondNotificationCountAfter = (await request(`/v1/spaces/${spaceId}/notifications`, { headers: headers(secondExecutor.accessToken) })).notifications
  .reduce((total, item) => total + Number(item.eventCount ?? 1), 0);
if (secondNotificationCountAfter !== secondNotificationCount) throw new Error("deselected executor received a task update notification");

await request(`/v1/spaces/${spaceId}/members/${accepted.membership.id}`, {
  method: "DELETE", headers: { Authorization: `Bearer ${admin.accessToken}` }
});
await expectError(`/v1/spaces/${spaceId}/snapshot`, 401, "SESSION_EXPIRED", { headers: headers(executor.accessToken) });

const rejoinInvitation = await request(`/v1/spaces/${spaceId}/invitations`, {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ email: executorEmail })
});
// A rejoin is a real new login after member removal; honor the production
// per-email challenge interval instead of weakening auth for the smoke test.
await new Promise((resolve) => setTimeout(resolve, 60_000));
const rejoinSession = await authenticate(executorEmail);
const rejoinPending = await request("/v1/invitations", { headers: headers(rejoinSession.accessToken) });
if (!rejoinPending.invitations.some((item) => item.id === rejoinInvitation.invitation.id)) throw new Error("removed account cannot see its new invitation");
const rejoined = await request(`/v1/invitations/${rejoinInvitation.invitation.id}/accept`, {
  method: "POST", headers: headers(rejoinSession.accessToken), body: JSON.stringify({})
});
await request("/v1/bootstrap", { headers: headers(rejoinSession.accessToken) });
await request(`/v1/spaces/${spaceId}/snapshot`, { headers: headers(rejoinSession.accessToken) });
await request(`/v1/spaces/${spaceId}/members/${rejoined.membership.id}`, {
  method: "DELETE", headers: { Authorization: `Bearer ${admin.accessToken}` }
});
await expectError(`/v1/spaces/${spaceId}/snapshot`, 401, "SESSION_EXPIRED", { headers: headers(rejoinSession.accessToken) });

await request(`/v1/spaces/${spaceId}/snapshot`, { headers: headers(secondExecutor.accessToken) });

const scheduledDelete = await request("/v1/account/deletion-requests", {
  method: "POST", headers: headers(admin.accessToken), body: JSON.stringify({ mode: "SCHEDULED" })
});
if (scheduledDelete.status !== "PENDING" || !scheduledDelete.executeAfter) throw new Error("scheduled deletion did not freeze the account");
await expectError("/v1/account", 401, "ACCOUNT_DELETION_PENDING", { headers: headers(admin.accessToken) });
await expectError(`/v1/spaces/${spaceId}/snapshot`, 401, "SPACE_DELETION_PENDING", { headers: headers(secondExecutor.accessToken) });
const recoverySession = await authenticate(smokeAdminEmail);
await request("/v1/account/deletion-cancellations", {
  method: "POST", headers: headers(recoverySession.accessToken), body: JSON.stringify({})
});
const restoredBootstrap = await request("/v1/bootstrap", { headers: headers(recoverySession.accessToken) });
if (!restoredBootstrap.memberships.some((item) => item.space.id === spaceId)) throw new Error("scheduled deletion cancellation did not restore the space");
const secondRecoverySession = await authenticate(secondExecutorEmail);
await request(`/v1/spaces/${spaceId}/snapshot`, { headers: headers(secondRecoverySession.accessToken) });
await request(`/v1/spaces/${spaceId}/members/${secondAccepted.membership.id}`, {
  method: "DELETE", headers: { Authorization: `Bearer ${recoverySession.accessToken}` }
});

const immediateDeleteEmail = `delete-now-${randomUUID()}@example.com`;
const immediateDeleteSession = await authenticate(immediateDeleteEmail);
const immediateDelete = await request("/v1/account/deletion-requests", {
  method: "POST", headers: headers(immediateDeleteSession.accessToken), body: JSON.stringify({ mode: "IMMEDIATE" })
});
if (immediateDelete.status !== "COMPLETED") throw new Error("immediate deletion did not complete");
await expectError("/v1/account", 401, "SESSION_EXPIRED", { headers: headers(immediateDeleteSession.accessToken) });

console.log("phase 3 local smoke passed: privacy gate, scoped exports, invitation/rejoin, targeted sync/results, revocation, scheduled deletion cancellation and immediate deletion");
