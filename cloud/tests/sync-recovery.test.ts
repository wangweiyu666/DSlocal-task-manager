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
  it("accepts a task name with 60 emoji code points", async () => {
    const emojiContent = { v: 1, b: "EmojiBatch000001", t: [{ i: "CloudTask0000001", n: "😀".repeat(60), r: 1 }] };
    expect(await submit(command("TASK_PUBLISH", undefined, { content: emojiContent }))).toMatchObject({ status: "accepted" });
  });

  it("validates effective exception STEPS at publish while allowing inherited definitions", async () => {
    const baseSteps = { v: 1, sv: 1, b: "StepsBatch000001", t: [{ i: "CloudTask0000001", n: "步骤", r: 1, u: { k: 5 }, s: [{ i: "Step000000000001", n: "一步", r: 1 }] }], e: [{ i: "CloudTask0000001", y: "2026-09-05" }] };
    expect(await submit(command("TASK_PUBLISH", undefined, { content: baseSteps }))).toMatchObject({ status: "accepted" });
    const cutOut = { ...baseSteps, b: "StepsBatch000002", e: [{ i: "CloudTask0000001", y: "2026-09-05", u: null }] };
    expect(await submit(command("TASK_UPDATE", undefined, { content: cutOut }, 1))).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    const cutOutWithSteps = { ...baseSteps, b: "StepsBatch000004", e: [{ i: "CloudTask0000001", y: "2026-09-05", u: null, s: [{ i: "Step000000000002", n: "不应保留", r: 1 }] }] };
    expect(await submit(command("TASK_UPDATE", undefined, { content: cutOutWithSteps }, 1))).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    const cutOutLeaf = { ...baseSteps, b: "StepsBatch000005", e: [{ i: "CloudTask0000001", y: "2026-09-05", u: { k: 3 }, s: [] }] };
    expect(await submit(command("TASK_UPDATE", undefined, { content: cutOutLeaf }, 1))).toMatchObject({ status: "accepted" });
    const normalToSteps = { ...baseSteps, b: "StepsBatch000003", t: [{ i: "CloudTask0000001", n: "普通", r: 1 }], e: [{ i: "CloudTask0000001", y: "2026-09-05", u: { k: 5 }, s: [{ i: "Step000000000002", n: "例外步骤", r: 1 }] }] };
    expect(await submit(command("TASK_UPDATE", undefined, { content: normalToSteps }, 2))).toMatchObject({ status: "accepted" });
  });

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
  it("derives conditional choice scores and rejects forged branches and values on retries", async () => {
    const a = "Option0000000001", b = "Option0000000002";
    const source = "Step000000000001", branch = "Step000000000002";
    const choiceContent = { v: 1, b: "ChoiceBatch00001", t: [{ i: "CloudTask0000001", n: "条件积分", r: 1, p: 3, u: { k: 5 }, s: [
      { i: source, n: "单选", r: 1, u: { k: 7, o: [{ i: a, n: "零分", p: 0 }, { i: b, n: "九分", p: 9 }] } },
      { i: branch, n: "通知", r: 1, u: { k: 6, t: "请知晓" }, c: { s: source, o: b } },
    ] }] };
    const published = await submit(command("TASK_PUBLISH", undefined, { content: choiceContent }));
    expect(published.status).toBe("accepted");
    const assignmentId = published.details!.assignmentId as string;
    const occurrenceKey = "CloudTask0000001:1:1:2026-09-05T00:00";
    expect(await submit(command("OCCURRENCE_UPSERT", occurrenceKey, { taskId: "CloudTask0000001", assignmentId, occurrenceKey, taskRevision: 1, timeZoneVersion: 1, localDate: "2026-09-05", scheduledAt: "2026-09-04T16:00:00Z" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const event = (stepResults: unknown[], extra = {}) => command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "RESULT_SUBMITTED", occurredAt: "2026-09-05T01:00:00Z", data: { status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "once", completedAt: "2026-09-05T01:00:00Z", stepResults, ...extra } });
    const valid = [{ stepId: source, status: "CONFIRMED", selectedOptionId: b }, { stepId: branch, status: "CONFIRMED" }];
    for (const invalid of [
      event(valid, { awardedPoints: 999 }),
      event([{ ...valid[0], selectedOptionId: "forged" }, valid[1]]),
      event([valid[0], { stepId: branch, status: "NOT_APPLICABLE" }]),
      event([valid[0], { stepId: branch, status: "SKIPPED" }]),
      event([{ ...valid[0], selectedOptionId: a }, valid[1]]),
      event([{ ...valid[0], selectedOptionId: a }, { stepId: branch, status: "NOT_APPLICABLE", selectedOptionId: a }]),
    ]) expect(await submit(invalid, "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    const completed = event(valid);
    expect(await submit(completed, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(await submit(completed, "EXECUTOR")).toMatchObject({ status: "duplicate" });
    const stored = JSON.parse(String(row("SELECT payload_json FROM execution_events WHERE id='" + completed.entityId + "'").payload_json));
    expect(stored).toMatchObject({ awardedPoints: 12, basePoints: 3, stepResults: [{ selectedOptionName: "九分", optionPoints: 9 }, { noticeContent: "请知晓" }] });
    const zero = event([{ ...valid[0], selectedOptionId: a }, { stepId: branch, status: "NOT_APPLICABLE" }]);
    expect(await submit(zero, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(JSON.parse(String(row("SELECT payload_json FROM execution_events WHERE id='" + zero.entityId + "'").payload_json)).awardedPoints).toBe(3);
  });

  it("snapshots standalone choices from date exceptions and rejects client supplied scores", async () => {
    const a = "Option0000000001", b = "Option0000000002";
    const task = { i: "CloudTask0000001", n: "单选", r: 1, p: 2, u: { k: 7, o: [{ i: a, n: "原选项", p: 3 }, { i: b, n: "其他", p: 0 }] } };
    const published = await submit(command("TASK_PUBLISH", undefined, { content: { v: 1, sv: 1, b: "ChoiceBatch00001", t: [task], e: [{ i: task.i, y: "2026-09-05", p: 4, u: { ...task.u, o: [{ i: a, n: "例外选项", p: 8 }, task.u.o[1]] } }] } }));
    expect(published.status).toBe("accepted");
    const assignmentId = published.details!.assignmentId as string, occurrenceKey = "CloudTask0000001:1:1:2026-09-05T00:00";
    expect(await submit(command("OCCURRENCE_UPSERT", occurrenceKey, { taskId: task.i, assignmentId, occurrenceKey, taskRevision: 1, timeZoneVersion: 1, localDate: "2026-09-05", scheduledAt: "2026-09-04T16:00:00Z" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const event = (extra = {}) => command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "COMPLETED", occurredAt: "2026-09-05T01:00:00Z", data: { status: "COMPLETED", executionKind: "CHOICE", localOccurrenceKey: "once", selectedOptionId: a, completedAt: "2026-09-05T01:00:00Z", ...extra } });
    expect(await submit(event({ optionPoints: 999 }), "EXECUTOR")).toMatchObject({ status: "rejected" });
    const completed = event();
    expect(await submit(completed, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(JSON.parse(String(row("SELECT payload_json FROM execution_events WHERE id='" + completed.entityId + "'").payload_json))).toMatchObject({ awardedPoints: 12, selectedOptionName: "例外选项", optionPoints: 8 });
  });
  it("validates and snapshots ordered STEPS results from the occurrence revision", async () => {
    const stepsContent = {
      v: 1, b: "StepsBatch000001", t: [{ i: "CloudTask0000001", n: "步骤任务", r: 1, u: { k: 5 }, s: [
        { i: "Step000000000001", n: "计数", r: 1, u: { k: 1, a: 2, v: 3 } },
        { i: "Step000000000002", n: "可跳过", r: 0 },
        { i: "Step000000000003", n: "计时", r: 1, u: { k: 2, v: 2 } },
        { i: "Step000000000004", n: "填写", r: 1, u: { k: 3 } },
        { i: "Step000000000005", n: "心情", r: 1, u: { k: 4 } },
      ] }],
    };
    const published = await submit(command("TASK_PUBLISH", undefined, { content: stepsContent }));
    expect(published.status).toBe("accepted");
    const assignmentId = published.details!.assignmentId as string;
    const occurrenceKey = "CloudTask0000001:1:1:2026-09-05T00:00";
    expect(await submit(command("OCCURRENCE_UPSERT", occurrenceKey, { taskId: "CloudTask0000001", assignmentId, occurrenceKey, taskRevision: 1, timeZoneVersion: 1, localDate: "2026-09-05", scheduledAt: "2026-09-04T16:00:00Z" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const event = (data: Record<string, unknown>, occurredAt = "2026-09-05T01:00:00Z") => command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "RESULT_SUBMITTED", data, occurredAt });
    const valid = event({ status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "", stepResults: [
      { stepId: "Step000000000001", status: "CONFIRMED", counterValue: 3 },
      { stepId: "Step000000000002", status: "SKIPPED" },
      { stepId: "Step000000000003", status: "CONFIRMED", elapsedMillis: 2000 },
      { stepId: "Step000000000004", status: "CONFIRMED", informationContent: "完成" },
      { stepId: "Step000000000005", status: "CONFIRMED", moodRating: 4, moodText: "不错" },
    ] });
    expect(await submit(valid, "EXECUTOR")).toMatchObject({ status: "accepted" });
    const stored = JSON.parse(String(row("SELECT payload_json FROM execution_events WHERE id='" + valid.entityId + "'").payload_json));
    expect(stored).toMatchObject({ executionKind: "STEPS", taskName: "步骤任务", taskDate: "2026-09-05", localOccurrenceKey: "" });
    expect(stored.stepResults).toEqual([
      { stepId: "Step000000000001", status: "CONFIRMED", name: "计数", required: true, execution: { k: 1, a: 2, v: 3 }, counterValue: 3 },
      { stepId: "Step000000000002", status: "SKIPPED", name: "可跳过", required: false, execution: null },
      { stepId: "Step000000000003", status: "CONFIRMED", name: "计时", required: true, execution: { k: 2, v: 2 }, elapsedMillis: 2000 },
      { stepId: "Step000000000004", status: "CONFIRMED", name: "填写", required: true, execution: { k: 3 }, informationContent: "完成" },
      { stepId: "Step000000000005", status: "CONFIRMED", name: "心情", required: true, execution: { k: 4 }, moodRating: 4, moodText: "不错" },
    ]);
    const beforeInvalid = count("execution_events");
    const completeResults = [
      { stepId: "Step000000000001", status: "CONFIRMED", counterValue: 3 }, { stepId: "Step000000000002", status: "SKIPPED" },
      { stepId: "Step000000000003", status: "CONFIRMED", elapsedMillis: 2000 }, { stepId: "Step000000000004", status: "CONFIRMED", informationContent: "完成" }, { stepId: "Step000000000005", status: "CONFIRMED", moodRating: 4, moodText: "不错" },
    ];
    const invalidCases = [
      completeResults.slice(0, 4),
      [completeResults[0], completeResults[1], completeResults[2], completeResults[3], { ...completeResults[4], stepId: "Step000000000004" }],
      [completeResults[1], completeResults[0], completeResults[2], completeResults[3], completeResults[4]],
      [{ ...completeResults[0], status: "SKIPPED" }, ...completeResults.slice(1)],
      [completeResults[0], completeResults[1], completeResults[2], { ...completeResults[3], informationContent: "   " }, completeResults[4]],
    ];
    for (const stepResults of invalidCases) {
      expect(await submit(event({ status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "", stepResults }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    }
    expect(count("execution_events")).toBe(beforeInvalid);
    const undo = command("EXECUTION_EVENT", uuidV7(), { assignmentId, occurrenceKey, taskRevision: 1, eventType: "COMPLETION_UNDONE", data: { status: "PENDING", localOccurrenceKey: "", taskName: "步骤任务", taskDate: "2026-09-05", executionKind: "STEPS" }, occurredAt: "2026-09-05T03:00:00Z" });
    expect(await submit(undo, "EXECUTOR")).toMatchObject({ status: "accepted" });
    const delayed = event({ status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "", stepResults: [
      { stepId: "Step000000000001", status: "CONFIRMED", counterValue: 3 }, { stepId: "Step000000000002", status: "SKIPPED" }, { stepId: "Step000000000003", status: "CONFIRMED", elapsedMillis: 2000 }, { stepId: "Step000000000004", status: "CONFIRMED", informationContent: "完成" }, { stepId: "Step000000000005", status: "CONFIRMED", moodRating: 4, moodText: "不错" },
    ] }, "2026-09-05T02:00:00Z");
    expect(await submit(delayed, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(row("SELECT execution_event_id FROM result_selections ORDER BY created_at DESC,rowid DESC LIMIT 1").execution_event_id).toBe(undo.entityId);
    expect(await submit(undo, "EXECUTOR")).toMatchObject({ status: "duplicate" });
    expect(await submit(event({ status: "MISSED", executionKind: "STEPS", localOccurrenceKey: "" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(JSON.parse(String(row("SELECT payload_json FROM execution_events WHERE id='" + valid.entityId + "'").payload_json))).toMatchObject({ status: "COMPLETED" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "", stepResults: [{ stepId: "Step000000000001", status: "CONFIRMED", counterValue: 4 }, { stepId: "Step000000000002", status: "SKIPPED" }, { stepId: "Step000000000003", status: "CONFIRMED", elapsedMillis: 2000 }, { stepId: "Step000000000004", status: "CONFIRMED", informationContent: "完成" }, { stepId: "Step000000000005", status: "CONFIRMED", moodRating: 4, moodText: "不错" }] }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
    expect(await submit(event({ status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "", stepResults: [{ stepId: "Step000000000001", status: "CONFIRMED", counterValue: 3 }, { stepId: "Step000000000002", status: "SKIPPED", informationContent: "draft" }, { stepId: "Step000000000003", status: "CONFIRMED", elapsedMillis: 2000 }, { stepId: "Step000000000004", status: "CONFIRMED", informationContent: "完成" }, { stepId: "Step000000000005", status: "CONFIRMED", moodRating: 4, moodText: "不错" }] }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
  });

  it("accepts valid mood completion data and rejects invalid rating, text, and task type", async () => {
    const moodContent = { v: 1, b: "CloudMoodBatch01", t: [{ i: "CloudTask0000001", n: "今天的心情怎么样", r: 1, s: [{ n: "旧版步骤", r: 1 }], u: { k: 4 } }] };
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
