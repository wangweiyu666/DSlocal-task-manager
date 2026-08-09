import { describe, expect, it } from "vitest";
import { createDraft, createRecurringDraftTask, createTemporaryDraftTask } from "../src/model/defaults";
import { buildBatch } from "../src/protocol/builder";

describe("temporary and recurring task creation", () => {
  it("creates a temporary task without an x rule", () => {
    const task = createTemporaryDraftTask();
    task.name = "临时整理";
    const draft = createDraft();
    draft.tasks = [task];

    expect(task.recurrence).toBeNull();
    expect(buildBatch(draft, []).t?.[0].x).toBeUndefined();
  });

  it("creates a recurring task with a daily x rule", () => {
    const task = createRecurringDraftTask();
    task.name = "每日整理";
    const draft = createDraft();
    draft.tasks = [task];

    expect(task.recurrence).toEqual({ f: 1 });
    expect(buildBatch(draft, []).t?.[0].x).toEqual({ f: 1 });
  });
});
