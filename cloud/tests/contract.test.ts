import { describe, expect, it } from "vitest";
import { readFile, readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { hmac, uuidV7 } from "../src/crypto";
import { normalizeEmail } from "../src/auth";
import { decodeCursor, encodeCursor } from "../src/cursor";
import { assertDst11Content, assertOccurrenceKey, parseCommands } from "../src/sync-contract";
import type { Env } from "../src/types";

const env = { AUTH_PEPPER: "test-only-cursor-secret" } as Env;

describe("cloud protocol primitives", () => {
  it("normalizes email without provider-specific alias rewriting", () => {
    expect(normalizeEmail(" User+Tag@Example.COM ")).toBe("user+tag@example.com");
  });

  it("creates sortable UUIDv7 identifiers", () => {
    const first = uuidV7(1_000);
    const second = uuidV7(2_000);
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(first < second).toBe(true);
  });

  it("domains HMAC values", async () => {
    expect(await hmac("secret", "access:value")).not.toBe(await hmac("secret", "refresh:value"));
  });

  it("binds signed cursors to their space and rejects tampering", async () => {
    const cursor = await encodeCursor(env, { kind: "changes", v: 1, spaceId: "space-a", sequence: 42 });
    await expect(decodeCursor(env, cursor, "changes", "space-a")).resolves.toMatchObject({ sequence: 42 });
    await expect(decodeCursor(env, `${cursor.slice(0, -1)}0`, "changes", "space-a")).rejects.toMatchObject({ code: "INVALID_REQUEST" });
    await expect(decodeCursor(env, cursor, "changes", "space-b")).rejects.toMatchObject({ code: "INVALID_REQUEST" });
  });

  it("strictly parses commands and validates deterministic occurrence keys", () => {
    const taskId = "CloudTask0000001";
    const occurrenceKey = `${taskId}:3:2:2026-08-17T09:30`;
    expect(() => assertOccurrenceKey(occurrenceKey, taskId, 3, 2)).not.toThrow();
    expect(() => assertOccurrenceKey(occurrenceKey, taskId, 4, 2)).toThrowError(/occurrenceKey/);
    expect(parseCommands({ commands: [{
      commandId: "0198f7b4-5b80-7a01-8ad2-7fe0c4331001",
      entityId: occurrenceKey,
      baseVersion: 0,
      createdAt: "2026-08-17T00:00:00Z",
      type: "OCCURRENCE_UPSERT",
      payload: {},
    }] })).toHaveLength(1);
    expect(parseCommands({ commands: [{
      commandId: "0198f7b4-5b80-7a01-8ad2-7fe0c4331002",
      entityId: "0198f7b4-5b80-7a01-8ad2-7fe0c4331003",
      baseVersion: 0,
      createdAt: "2026-08-17T00:00:00Z",
      type: "INFORMATION_SUBMISSION",
      payload: {},
    }] })).toHaveLength(1);
    expect(() => parseCommands({ commands: [{
      commandId: "0198f7b4-5b80-7a01-8ad2-7fe0c4331001",
      entityId: "CloudTask0000001",
      baseVersion: 0,
      createdAt: "2026-08-17T00:00:00Z",
      type: "TASK_CANCEL",
      payload: {},
      unexpected: true,
    }] })).toThrowError(/未知字段/);
  });

  it("accepts one-task DST1 and DST1.1 canonical payloads", () => {
    const task = { i: "CloudTask0000001", n: "巡检", r: 1, y: "2026-08-17" };
    expect(assertDst11Content({ v: 1, b: "CloudBatch000001", t: [task] })).toBeTruthy();
    expect(assertDst11Content({ v: 1, b: "CloudBatch000004", g: [{ i: "CloudGroup000001", n: "日常", cm: "完成", im: "未完成", t: [task] }] })).toBeTruthy();
    expect(() => assertDst11Content({ v: 1, b: "CloudBatch000005", t: [task], g: [{ i: "CloudGroup000001", t: [task] }] })).toThrowError(/包含一个/);
    expect(assertDst11Content({ v: 1, sv: 1, b: "CloudBatch000002", t: [task], e: [{ i: task.i, y: "2026-08-18", c: 1 }] })).toBeTruthy();
    expect(() => assertDst11Content({ v: 1, sv: 1, b: "CloudBatch000003", t: [task], e: [] })).toThrowError(/非空/);
    expect(() => assertDst11Content({ v: 1, sv: 1, b: "CloudBatch000003", t: [task] })).toThrowError(/例外数组/);
  });
});

describe("shared cloud vectors", () => {
  it("manifest references existing JSON files", async () => {
    const root = fileURLToPath(new URL("../../cloud-protocol-test-vectors/", import.meta.url));
    const manifest = JSON.parse(await readFile(`${root}/cloud-manifest.json`, "utf8")) as { vectors: Array<{ file: string; valid: boolean }> };
    expect(manifest.vectors.length).toBeGreaterThanOrEqual(7);
    const groups = new Set(await readdir(root));
    expect(groups).toEqual(expect.objectContaining(new Set(["valid", "invalid"])));
    for (const vector of manifest.vectors) {
      const parsed = JSON.parse(await readFile(`${root}/${vector.file}`, "utf8"));
      expect(parsed).toBeTruthy();
    }
  });
});
