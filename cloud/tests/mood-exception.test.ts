import { afterEach, beforeEach, describe, expect, it } from "vitest";
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
  const request = new Request("https://example.invalid/commands", {
    method: "POST",
    headers: { Authorization: `Bearer ${role}` },
    body: JSON.stringify({ commands: [value] }),
  });
  const response = await submitCommands(database.env, request, database.spaceId);
  return ((await response.json()) as { results: CommandResult[] }).results[0];
}

const TASK_ID = "CloudTask0000001";
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

describe("cloud mood execution exceptions", () => {
  it("resolves a date exception from NORMAL to MOOD for the occurrence", async () => {
    const assignmentId = await publishAndOpen({
      v: 1,
      sv: 1,
      b: "CloudMoodExc0001",
      t: [{ i: TASK_ID, n: "每日任务", r: 1, x: { f: 1, s: DATE, c: 3 } }],
      e: [{ i: TASK_ID, y: DATE, u: { k: 4 } }],
    });

    expect(await submit(event(assignmentId, {
      status: "COMPLETED", executionKind: "MOOD", moodRating: 4, moodText: "很好",
    }), "EXECUTOR")).toMatchObject({ status: "accepted" });
  });

  it("rejects MOOD data when a MOOD base is overridden to NORMAL on that date", async () => {
    const assignmentId = await publishAndOpen({
      v: 1,
      sv: 1,
      b: "CloudMoodExc0002",
      t: [{ i: TASK_ID, n: "每日心情", r: 1, x: { f: 1, s: DATE, c: 3 }, u: { k: 4 } }],
      e: [{ i: TASK_ID, y: DATE, u: null }],
    });

    expect(await submit(event(assignmentId, {
      status: "COMPLETED", executionKind: "MOOD", moodRating: 4, moodText: "不应接受",
    }), "EXECUTOR")).toMatchObject({ status: "rejected", code: "INVALID_REQUEST" });
  });
});
