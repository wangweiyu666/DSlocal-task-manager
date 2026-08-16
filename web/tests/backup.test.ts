import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it } from "vitest";
import { createBackup, importBackup, parseBackup } from "../src/db/backup";
import { db, getSettings } from "../src/db/database";
import { emptyTaskFields } from "../src/model/defaults";

describe("DSDOM backup", () => {
  beforeEach(async () => { await db.delete(); await db.open(); });

  it("round-trips a versioned configuration", async () => {
    const settings = await getSettings();
    await db.groups.put({ id: "ExampleGroup0001", name: "示例", completeMessage: "完成", incompleteMessage: "继续", order: 0, createdAt: settings.updatedAt, updatedAt: settings.updatedAt });
    const backup = await createBackup();
    const parsed = parseBackup(JSON.stringify(backup));
    await db.groups.clear();
    await importBackup(parsed, "replace");
    expect(await db.groups.get("ExampleGroup0001")).toMatchObject({ name: "示例" });
  });

  it("rejects an unversioned JSON file", () => {
    expect(() => parseBackup('{"groups":[]}')).toThrow(/结构无效/u);
  });

  it("round-trips DSDOM v1.2 exceptions and reads v1 as 1.0", async () => {
    const now = new Date().toISOString();
    await db.tasks.put({
      ...emptyTaskFields(), id: "RepeatTask000001", name: "每日任务", recurrence: { f: 1 }, groupId: null,
      createdAt: now, updatedAt: now, lastGeneratedAt: null, version: 0,
    });
    await db.taskExceptions.put({
      id: "RepeatTask000001|2026-08-18",
      taskId: "RepeatTask000001",
      date: "2026-08-18",
      directive: { i: "RepeatTask000001", y: "2026-08-18", c: 1 },
      createdAt: now,
      updatedAt: now,
    });
    const backup = await createBackup();
    expect(backup).toMatchObject({ version: 1, minorVersion: 2 });
    expect(parseBackup(JSON.stringify(backup)).taskExceptions).toHaveLength(1);

    const legacy = { ...backup, minorVersion: undefined, taskExceptions: undefined, exceptionRevisions: undefined };
    const parsedLegacy = parseBackup(JSON.stringify(legacy));
    expect(parsedLegacy.taskExceptions).toEqual([]);
    expect(parsedLegacy.exceptionRevisions).toEqual([]);
  });
});
