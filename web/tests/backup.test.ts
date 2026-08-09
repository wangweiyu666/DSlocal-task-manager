import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it } from "vitest";
import { createBackup, importBackup, parseBackup } from "../src/db/backup";
import { db, getSettings } from "../src/db/database";

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
});
