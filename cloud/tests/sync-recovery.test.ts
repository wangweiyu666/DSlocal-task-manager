import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { authenticate, refreshSession } from "../src/auth";
import { uuidV7 } from "../src/crypto";
import { submitCommands } from "../src/sync";
import type { CommandResult, SyncCommand } from "../src/sync-contract";
import { testDatabase } from "./d1-test-db";

let database: Awaited<ReturnType<typeof testDatabase>>;
beforeEach(async () => { database = await testDatabase(); });
afterEach(() => { database.sqlite.close(); });

function command(type: SyncCommand["type"], entityId = "CloudTask0000001", payload = {}, baseVersion = 0): SyncCommand {
  return { commandId: uuidV7(), entityId, baseVersion, createdAt: new Date().toISOString(), type, payload };
}
async function submit(value: SyncCommand, role = "ADMIN"): Promise<CommandResult> {
  const request = new Request("https://example.invalid/commands", { method: "POST", headers: { Authorization: `Bearer ${role}` }, body: JSON.stringify({ commands: [value] }) });
  const response = await submitCommands(database.env, request, database.spaceId);
  return ((await response.json()) as { results: CommandResult[] }).results[0];
}
function row(query: string): Record<string, unknown> { return database.sqlite.prepare(query).get() as Record<string, unknown>; }
function count(table: string): number { return Number(row(`SELECT COUNT(*) AS count FROM ${table}`).count); }
const content = { v: 1, b: "CloudBatch000001", t: [{ i: "CloudTask0000001", n: "测试", r: 1 }] };

describe("durable command results", () => {
  it("replays rejection and conflict without reporting success", async () => {
    const rejected = command("TASK_CANCEL");
    expect(await submit(rejected)).toMatchObject({ status: "rejected", code: "NOT_FOUND" });
    expect(await submit(rejected)).toMatchObject({ status: "rejected", code: "NOT_FOUND" });
    expect(await submit(command("TASK_PUBLISH", undefined, { content }))).toMatchObject({ status: "accepted" });
    const conflict = command("TASK_UPDATE", undefined, { content });
    expect(await submit(conflict)).toMatchObject({ status: "conflict" });
    expect(await submit(conflict)).toMatchObject({ status: "conflict" });
  });

  it("rolls back all preceding writes when the final command receipt fails", async () => {
    // Fail after business data, notifications and audit writes to exercise the whole rollback.
    database.controls.failOnce = /INSERT INTO command_receipts/;
    const publish = command("TASK_PUBLISH", undefined, { content });
    expect(await submit(publish)).toMatchObject({ status: "retryable" });
    for (const table of ["tasks", "task_revisions", "assignments", "space_entities", "space_changes", "notifications", "audit_events", "command_receipts", "sync_write_guards"]) expect(count(table), table).toBe(0);
    const accepted = await submit(publish);
    expect(accepted.status).toBe("accepted");
    expect(accepted.changeSequence).toBe(count("space_changes"));
    expect(await submit(publish)).toEqual({ ...accepted, status: "duplicate" });
    expect(count("tasks")).toBe(1);
    expect(count("notifications")).toBe(1);
  });

  it("rejects stale planning without committing side effects", async () => {
    database.controls.beforeBatch = () => database.sqlite.prepare("UPDATE spaces SET current_sequence=current_sequence+1 WHERE id=?").run(database.spaceId);
    const publish = command("TASK_PUBLISH", undefined, { content });
    expect(await submit(publish)).toMatchObject({ status: "retryable" });
    expect(count("tasks")).toBe(0);
    expect(count("command_receipts")).toBe(0);
    expect(await submit(publish)).toMatchObject({ status: "accepted" });
  });

  it("commits a simultaneous duplicate delivery only once", async () => {
    const publish = command("TASK_PUBLISH", undefined, { content });
    const results = await Promise.all([submit(publish), submit(publish)]);
    expect(results.map((result) => result.status).sort()).toEqual(["accepted", "duplicate"]);
    expect(count("tasks")).toBe(1);
    expect(count("notifications")).toBe(1);
  });

  it("rejects malformed task data before saving revisions or assignments", async () => {
    const result = await submit(command("TASK_PUBLISH", undefined, { content: { ...content, t: [{ i: "CloudTask0000001" }] } }));
    expect(result).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(count("tasks")).toBe(0);
    expect(count("assignments")).toBe(0);
  });
});

describe("session rotation", () => {
  it("returns one valid token bundle to concurrent refresh requests and lost-response retries", async () => {
    const request = () => new Request("https://example.invalid/refresh", { method: "POST", body: JSON.stringify({ refreshToken: "ADMIN" }) });
    const [a, b] = await Promise.all([refreshSession(database.env, request()).then((r) => r.json()), refreshSession(database.env, request()).then((r) => r.json())]) as Array<{ accessToken: string }>;
    expect(a).toEqual(b);
    expect(await (await refreshSession(database.env, request())).json()).toEqual(a);
    await expect(authenticate(database.env, new Request("https://example.invalid/account", { headers: { Authorization: `Bearer ${a.accessToken}` } }))).resolves.toMatchObject({ accountId: "ADMIN" });
  });
});

describe("completion undo", () => {
  it("accepts valid mood completion data and rejects invalid rating, text, and task type", async () => {
    const moodContent = { v: 1, b: "CloudMoodBatch01", t: [{ i: "CloudTask0000001", n: "今天的心情怎么样", r: 1, u: { k: 4 } }] };
    const published = await submit(command("TASK_PUBLISH", undefined, { content: moodContent }));
    const assignmentId = published.details!.assignmentId as string;
    const occurrenceKey = "CloudTask0000001:1:1:2026-09-05T00:00";
    expect(await submit(command("OCCURRENCE_UPSERT", occurrenceKey, { taskId: "CloudTask0000001", assignmentId, occurrenceKey, taskRevision: 1, timeZoneVersion: 1, localDate: "2026-09-05", scheduledAt: "2026-09-04T16:00:00Z" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const event = (data: Record<string, unknown>) => command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "RESULT_SUBMITTED", data, occurredAt: "2026-09-05T01:00:00Z" });

    expect(await submit(event({ status: "COMPLETED", executionKind: "MOOD", moodRating: 4, moodText: "很好" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "MOOD" }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "MOOD", moodRating: 0, moodText: "无效" }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "MOOD", moodRating: 6, moodText: "无效" }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "MOOD", moodRating: 4, moodText: "😀".repeat(2001) }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "NORMAL", moodRating: 4, moodText: "错误类型" }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "COMPLETION_UNDONE", data: { status: "PENDING", executionKind: "MOOD", moodRating: 4, moodText: "撤销不应携带答案" }, occurredAt: "2026-09-05T01:01:00Z" }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
  });

  it("syncs a completed → pending → completed transition and ignores a delayed old undo", async () => {
    const moodContent = { v: 1, b: "CloudMoodBatch02", t: [{ i: "CloudTask0000001", n: "今天的心情怎么样", r: 1, u: { k: 4 } }] };
    const published = await submit(command("TASK_PUBLISH", undefined, { content: moodContent }));
    const assignmentId = published.details!.assignmentId as string;
    const occurrenceKey = "CloudTask0000001:1:1:2026-09-05T00:00";
    expect(await submit(command("OCCURRENCE_UPSERT", occurrenceKey, { taskId: "CloudTask0000001", assignmentId, occurrenceKey, taskRevision: 1, timeZoneVersion: 1, localDate: "2026-09-05", scheduledAt: "2026-09-04T16:00:00Z" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const event = (eventType: string, data: Record<string, unknown>, occurredAt: string) => command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType, data, occurredAt });
    const complete = event("RESULT_SUBMITTED", { status: "COMPLETED", executionKind: "MOOD", moodRating: 1, moodText: "低落" }, "2026-09-05T01:00:00Z");
    const undo = event("COMPLETION_UNDONE", { status: "PENDING", executionKind: "MOOD" }, "2026-09-05T01:01:00Z");
    const redo = event("RESULT_SUBMITTED", { status: "COMPLETED", executionKind: "MOOD", moodRating: 5, moodText: "很好" }, "2026-09-05T01:02:00Z");
    for (const value of [complete, undo, redo]) {
      expect(await submit(value, "EXECUTOR")).toMatchObject({ status: "accepted" });
      const stored = row("SELECT payload_json FROM execution_events WHERE id='" + value.entityId + "'");
      expect(JSON.parse(String(stored.payload_json))).toEqual(value.payload.data);
      const selected = row("SELECT execution_event_id FROM result_selections ORDER BY created_at DESC,rowid DESC LIMIT 1");
      expect(selected!.execution_event_id).toBe(value.entityId);
      const projection = row("SELECT payload_json FROM space_entities WHERE entity_type='result_selection' ORDER BY change_sequence DESC LIMIT 1");
      expect(JSON.parse(String(projection!.payload_json)).executionEventId).toBe(value.entityId);
    }
    expect(await submit(undo, "EXECUTOR")).toMatchObject({ status: "duplicate" });
    await submit(event("COMPLETION_UNDONE", { status: "PENDING", executionKind: "MOOD" }, "2026-09-05T01:01:00Z"), "EXECUTOR");
    expect(row("SELECT execution_event_id FROM result_selections ORDER BY created_at DESC,rowid DESC LIMIT 1")!.execution_event_id).toBe(redo.entityId);
    expect(await submit(command("RESULT_SELECT", uuidV7(), { assignmentId, occurrenceKey, executionEventId: undo.entityId, reason: "管理员确认撤销" }))).toMatchObject({ status: "accepted" });
    await submit(event("RESULT_SUBMITTED", { status: "COMPLETED", executionKind: "MOOD", moodRating: 5, moodText: "很好" }, "2026-09-05T01:03:00Z"), "EXECUTOR");
    expect(row("SELECT execution_event_id FROM result_selections ORDER BY created_at DESC,rowid DESC LIMIT 1")!.execution_event_id).toBe(undo.entityId);
  });
});
