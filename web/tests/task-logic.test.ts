import { describe, expect, it } from "vitest";
import { createDraft, createRecurringDraftTask, createTemporaryDraftTask } from "../src/model/defaults";
import { buildBatch, taskRecordFromDraft } from "../src/protocol/builder";

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
});
