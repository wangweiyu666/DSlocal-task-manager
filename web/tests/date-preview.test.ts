import { beforeEach, describe, expect, it, vi } from "vitest";
import { applyDefaultDates, naturalDate } from "../src/pages/CreatePage";
import { createDraft, createDraftTask } from "../src/model/defaults";
import { buildBatch } from "../src/protocol/builder";

describe("frozen preview dates", () => {
  beforeEach(() => vi.setSystemTime(new Date("2026-08-23T17:30:00.000Z")));
  it("uses the selected timezone across midnight for grouped and ungrouped tasks", () => {
    const draft = createDraft();
    const one = createDraftTask({ name: "未分组" });
    const two = { ...createDraftTask({ name: "分组" }), groupId: "Group00000000001" };
    draft.tasks = [one, two];
    const batch = applyDefaultDates(buildBatch(draft, [{ id: "Group00000000001", name: "组", completeMessage: "", incompleteMessage: "", order: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }]), draft, "Asia/Hong_Kong");
    expect(batch.t?.[0].y).toBe("2026-08-24");
    expect(batch.g?.[0].t?.[0].y).toBe("2026-08-24");
    expect(naturalDate("UTC")).toBe("2026-08-23");
  });
  it("preserves untouched old empty dates but resolves explicit clear and recurring start", () => {
    const draft = createDraft();
    const old = createDraftTask({ name: "旧", taskDate: "", taskDateIntent: "preserve" }, "existing");
    const cleared = createDraftTask({ name: "清空", taskDate: "", taskDateIntent: "clear" }, "existing");
    const recurring = createDraftTask({ name: "重复", recurrence: { f: 1 }, taskDateIntent: "set" });
    draft.tasks = [old, cleared, recurring];
    const batch = applyDefaultDates(buildBatch(draft, []), draft, "Asia/Hong_Kong");
    expect(batch.t?.find((task) => task.i === old.taskId)?.y).toBeUndefined();
    expect(batch.t?.find((task) => task.i === cleared.taskId)?.y).toBe("2026-08-24");
    expect(batch.t?.find((task) => task.i === recurring.taskId)?.x?.s).toBe("2026-08-24");
  });
});
