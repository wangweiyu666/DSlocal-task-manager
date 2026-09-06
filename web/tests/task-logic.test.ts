import { describe, expect, it } from "vitest";
import { createDraft, createRecurringDraftTask, createTemporaryDraftTask, draftTaskFromTask } from "../src/model/defaults";
import { buildBatch, taskRecordFromDraft } from "../src/protocol/builder";
import { compactTask } from "../src/protocol/compact";
import { validateDst1Batch } from "../src/protocol/validation";

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

  it("uses the recurrence rule instead of hidden single-task scheduling fields", () => {
    const task = createRecurringDraftTask();
    task.name = "每日整理";
    task.taskDate = "2026-08-09";
    task.deadlineMode = "datetime";
    task.deadline = "2026-08-09T20:00";
    task.reminders = [10];
    const draft = createDraft();
    draft.tasks = [task];

    const output = buildBatch(draft, []).t?.[0];
    expect(output?.x).toEqual({ f: 1 });
    expect(output?.y).toBeUndefined();
    expect(output?.l).toBeUndefined();
    expect(output?.h).toBeUndefined();

    const saved = taskRecordFromDraft(task);
    expect(saved.taskDate).toBe("");
    expect(saved.deadlineMode).toBe("default");
    expect(saved.deadline).toBe("");
    expect(saved.reminders).toEqual([]);
  });

  it("preserves mood execution and default name in the protocol", () => {
    const task = createTemporaryDraftTask();
    task.name = "今天的心情怎么样";
    task.execution = { k: 4 };
    const draft = createDraft();
    draft.tasks = [task];

    expect(buildBatch(draft, []).t?.[0].u).toEqual({ k: 4 });
    expect(compactTask({ i: task.taskId, n: task.name, r: 1, u: { k: 4 } }).u).toEqual({ k: 4 });
  });

  it("assigns stable legacy step IDs before editing and preserves them through reorder", () => {
    const task = {
      ...taskRecordFromDraft({ ...createTemporaryDraftTask(), name: "旧任务", steps: [{ n: "第一步", r: 1 }, { n: "第二步", r: 0 }] }),
      id: "TaskLegacy00001A",
    };
    const draft = draftTaskFromTask(task);
    const firstId = draft.steps[0].i;
    const secondId = draft.steps[1].i;
    expect(firstId).toBe("sTaskLegacy00" + "000");
    expect(secondId).toBe("sTaskLegacy00" + "001");
    draft.steps.reverse();
    const batch = buildBatch({ ...createDraft(), tasks: [draft] }, []);
    expect(batch.t?.[0].s?.map((step) => step.i)).toEqual([secondId, firstId]);
  });

  it("requires a complete, uniquely identified step set for STEPS", () => {
    const task = { i: "TaskSteps000001A", n: "分步", r: 1 as const, u: { k: 5 as const }, s: [{ i: "Step000000000001", n: "确认", r: 1 as const }] };
    expect(() => validateDst1Batch({ v: 1, b: "BatchSteps00001A", t: [task] })).not.toThrow();
    expect(() => validateDst1Batch({ v: 1, b: "BatchSteps00001A", t: [{ ...task, s: [{ ...task.s[0], i: "Step000000000001" }, { ...task.s[0], i: "Step000000000001" }] }] })).toThrowError(expect.objectContaining({ code: "DUPLICATE_VALUE" }));
  });
});
