import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { uuidV7 } from "../src/crypto";
import { submitCommands, snapshot } from "../src/sync";
import type { CommandResult, SyncCommand } from "../src/sync-contract";
import { testDatabase } from "./d1-test-db";

let database: Awaited<ReturnType<typeof testDatabase>>;
beforeEach(async () => { database = await testDatabase(); });
afterEach(() => { database.sqlite.close(); });

function command(type: SyncCommand["type"], entityId = "Task000000000001", payload = {}, baseVersion = 0): SyncCommand {
  return { commandId: uuidV7(), entityId, baseVersion, createdAt: new Date().toISOString(), type, payload };
}

async function submit(value: SyncCommand, role = "ADMIN"): Promise<CommandResult> {
  const request = new Request("https://example.invalid/commands", {
    method: "POST",
    headers: { Authorization: `Bearer ${role}` },
    body: JSON.stringify({ commands: [value] }),
  });
  const response = await submitCommands(database.env, request, database.spaceId);
  return ((await response.json()) as { results: CommandResult[] }).results[0];
}

const TASK_ID = "Task000000000001";
const DATE = "2026-09-05";
const OCCURRENCE_KEY = `${TASK_ID}:1:1:${DATE}T00:00`;

async function publishAndOpen(content: Record<string, unknown>) {
  const published = await submit(command("TASK_PUBLISH", undefined, { content }));
  expect(published).toMatchObject({ status: "accepted" });
  const assignmentId = published.details!.assignmentId as string;
  expect(await submit(command("OCCURRENCE_UPSERT", OCCURRENCE_KEY, {
    taskId: TASK_ID,
    assignmentId,
    occurrenceKey: OCCURRENCE_KEY,
    taskRevision: 1,
    timeZoneVersion: 1,
    localDate: DATE,
    scheduledAt: "2026-09-04T16:00:00Z",
  }), "EXECUTOR")).toMatchObject({ status: "accepted" });
  return assignmentId;
}

function event(assignmentId: string, data: Record<string, unknown>) {
  return command("EXECUTION_EVENT", uuidV7(), {
    assignmentId,
    occurrenceKey: OCCURRENCE_KEY,
    taskRevision: 1,
    eventType: "RESULT_SUBMITTED",
    data,
    occurredAt: "2026-09-05T01:00:00Z",
  });
}

describe("temperature execution", () => {
  const content = (u: unknown = { k: 8 }, steps?: unknown[]) => ({ v: 1, b: "Batch00000000001", t: [{ i: TASK_ID, n: "体温询问", r: 1, p: 3, ...(u ? { u } : {}), ...(steps ? { s: steps } : {}) }] });
  const answer = (value: unknown = 365) => ({ status: "COMPLETED", executionKind: "TEMPERATURE", localOccurrenceKey: "once", temperatureTenthsC: value });

  it.each([350, 365, 420])("temperature accepts boundary %s and duplicate commands remain idempotent", async (value) => {
    const assignmentId = await publishAndOpen(content());
    const completion = event(assignmentId, answer(value));
    expect(await submit(completion, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(await submit(completion, "EXECUTOR")).toMatchObject({ status: "duplicate" });
    const rows = database.sqlite.prepare("SELECT payload_json FROM execution_events").all() as Array<{ payload_json: string }>;
    expect(rows).toHaveLength(1);
    expect(JSON.parse(rows[0].payload_json)).toMatchObject({ temperatureTenthsC: value, taskName: "体温询问", awardedPoints: 3 });
  });

  it.each([null, 349, 421, 365.5, "365"]) ("temperature rejects invalid reading %s", async (value) => {
    const assignmentId = await publishAndOpen(content());
    expect(await submit(event(assignmentId, answer(value)), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
  });

  it("temperature rejects missing answers, MISSED answers and answers for another execution kind", async () => {
    const assignmentId = await publishAndOpen(content());
    expect(await submit(event(assignmentId, { status: "COMPLETED", executionKind: "TEMPERATURE", localOccurrenceKey: "once" }), "EXECUTOR")).toMatchObject({ status: "rejected" });
    expect(await submit(event(assignmentId, { ...answer(), status: "MISSED" }), "EXECUTOR")).toMatchObject({ status: "rejected" });
    expect(await submit(event(assignmentId, { status: "MISSED", executionKind: "TEMPERATURE", localOccurrenceKey: "once" }), "EXECUTOR")).toMatchObject({ status: "accepted" });
  });

  it("temperature validation resolves occurrence exceptions against the submitted revision", async () => {
    const assignmentId = await publishAndOpen({ v: 1, b: "Batch00000000001", t: [{ i: TASK_ID, n: "每日体温", r: 1, x: { f: 1, s: DATE, c: 3 } }], sv: 1, e: [{ i: TASK_ID, y: DATE, u: { k: 8 } }] });
    expect(await submit(event(assignmentId, answer()), "EXECUTOR")).toMatchObject({ status: "accepted" });
  });

  it("temperature data is rejected for normal tasks", async () => {
    const assignmentId = await publishAndOpen(content(null));
    expect(await submit(event(assignmentId, answer()), "EXECUTOR")).toMatchObject({ status: "rejected" });
  });

  it("temperature steps accept confirmed readings and forbid answers on skipped steps", async () => {
    const steps = [{ i: "Step000000000001", n: "必需体温", r: 1, u: { k: 8 } }, { i: "Step000000000002", n: "选做体温", r: 0, u: { k: 8 } }];
    const assignmentId = await publishAndOpen(content({ k: 5 }, steps));
    const data = { status: "COMPLETED", executionKind: "STEPS", localOccurrenceKey: "once", stepResults: [{ stepId: steps[0].i, status: "CONFIRMED", temperatureTenthsC: 365 }, { stepId: steps[1].i, status: "SKIPPED" }] };
    expect(await submit(event(assignmentId, { ...data, stepResults: [data.stepResults[0], { ...data.stepResults[1], temperatureTenthsC: 365 }] }), "EXECUTOR")).toMatchObject({ status: "rejected" });
    expect(await submit(event(assignmentId, data), "EXECUTOR")).toMatchObject({ status: "accepted" });
    const row = database.sqlite.prepare("SELECT payload_json FROM execution_events").get() as { payload_json: string };
    expect(JSON.parse(row.payload_json).stepResults[0]).toMatchObject({ temperatureTenthsC: 365, execution: { k: 8 }, name: "必需体温" });
  });
  it("temperature duplicate candidates and administrator selection preserve the chosen reading", async () => {
    const assignmentId = await publishAndOpen(content());
    const first = event(assignmentId, answer(365));
    const second = event(assignmentId, answer(370));
    expect(await submit(first, "EXECUTOR")).toMatchObject({ status: "accepted" });
    expect(await submit(second, "EXECUTOR")).toMatchObject({ status: "accepted", details: { duplicateOf: first.entityId } });
    expect(await submit(command("RESULT_SELECT", uuidV7(), { assignmentId, occurrenceKey: OCCURRENCE_KEY, executionEventId: second.entityId, reason: "采用更正体温" }))).toMatchObject({ status: "accepted" });
    const row = database.sqlite.prepare("SELECT e.payload_json FROM result_selections r JOIN execution_events e ON e.id=r.execution_event_id ORDER BY r.created_at DESC,r.rowid DESC LIMIT 1").get() as { payload_json: string };
    expect(JSON.parse(row.payload_json).temperatureTenthsC).toBe(370);
    const response = await snapshot(database.env, new Request("https://example.invalid/snapshot", { headers: { Authorization: "Bearer ADMIN" } }), database.spaceId);
    const data = await response.json() as { entities: Array<{ entityType: string; changeSequence: number }> };
    const selections = data.entities.filter((item) => item.entityType === "result_selection");
    expect(selections).toHaveLength(2);
    expect(selections.every((item) => Number.isInteger(item.changeSequence) && item.changeSequence > 0)).toBe(true);
    expect(new Set(selections.map((item) => item.changeSequence)).size).toBe(2);
  });

});
