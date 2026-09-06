import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { db } from "../src/db/database";
import { createDraft, createDraftTask } from "../src/model/defaults";
import { createPreviewSnapshot, commitPreviewSnapshot } from "../src/model/preview";
import { decodeDst1 } from "../src/protocol/dst1";

describe("preview snapshot commit", () => {
  beforeEach(async () => { await db.delete(); await db.open(); vi.setSystemTime(new Date("2026-08-23T17:30:00.000Z")); });
  it("commits one frozen preview after time advances and keeps grouped/un grouped revisions aligned", async () => {
    const draft = createDraft("冻结");
    const group = { id: "Group00000000001", name: "组", completeMessage: "", incompleteMessage: "", order: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    const first = createDraftTask({ name: "未分组" });
    const second = { ...createDraftTask({ name: "分组" }), groupId: group.id };
    first.name = "  示例  "; first.steps = [{ i: "Step000000000001", n: "e\u0301", r: 1 }];
    draft.tasks = [first, second];
    draft.exceptions = [{ draftItemId: "ExceptionItem0001", directive: { i: first.taskId, y: "2026-08-24", n: "例外名称" } }];
    await db.drafts.put(draft);
    const preview = createPreviewSnapshot(draft, [group], "Asia/Hong_Kong");
    draft.tasks[0].name = "后来修改";
    vi.setSystemTime(new Date("2026-08-24T17:30:00.000Z"));
    await commitPreviewSnapshot(preview);
    const history = await db.batchHistory.get(preview.batch.b);
    expect(history?.snapshot).toEqual(preview.batch);
    expect(decodeDst1(preview.encoded.envelope).batch).toMatchObject(history?.snapshot ?? {});
    expect((await db.tasks.get(first.taskId))?.name).toBe("示例");
    expect((await db.tasks.get(first.taskId))?.steps).toEqual(preview.batch.t?.find((task) => task.i === first.taskId)?.s ?? []);
    expect((await db.tasks.get(second.taskId))?.taskDate).toBe("2026-08-24");
    const revisions = await db.taskRevisions.toArray();
    expect(revisions.map((item) => item.snapshot.i).sort()).toEqual([first.taskId, second.taskId].sort());
    expect(revisions.every((item) => item.snapshot.y === "2026-08-24")).toBe(true);
    expect(preview.batch.t?.[0].y).toBe("2026-08-24");
    const exception = await db.taskExceptions.get(`${first.taskId}|2026-08-24`);
    expect(exception?.directive).toEqual(preview.batch.e?.[0]);
    expect((await db.exceptionRevisions.toArray())[0]?.snapshot).toEqual(exception?.directive);
  });
  it("rolls back an injected transaction failure and retries the same snapshot", async () => {
    const draft = createDraft(); draft.tasks = [createDraftTask({ name: "重试" })];
    const preview = createPreviewSnapshot(draft, [], "UTC");
    const originalPut = db.tasks.put.bind(db.tasks);
    let failed = true;
    const spy = vi.spyOn(db.tasks, "put").mockImplementation((...args) => { if (failed) { failed = false; return Promise.reject(new Error("injected")) as ReturnType<typeof db.tasks.put>; } return originalPut(...args); });
    await expect(commitPreviewSnapshot(preview)).rejects.toThrow("injected");
    expect(await db.batchHistory.get(preview.batch.b)).toBeUndefined();
    spy.mockRestore();
    await commitPreviewSnapshot(preview);
    expect(await db.batchHistory.get(preview.batch.b)).toBeDefined();
  });
});
