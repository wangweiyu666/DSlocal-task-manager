import "fake-indexeddb/auto";
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { TaskEditor, taskIssues, type EditableTask } from "../src/components/TaskEditor";
import { ExceptionEditor } from "../src/components/ExceptionEditor";
import { createTemporaryDraftTask } from "../src/model/defaults";
import { draftTaskToDst1, taskRecordFromDraft } from "../src/protocol/builder";
import { fieldsFromDst1 } from "../src/model/converters";
import { encodeDst1, decodeDst1 } from "../src/protocol/dst1";
import { validateDst1Batch } from "../src/protocol/validation";
import { reminderForTaskDay } from "../../shared/protocol/reminders";
import { db } from "../src/db/database";
import { createBackup, parseBackup } from "../src/db/backup";
import type { Dst11Exception, Dst1Task } from "../src/protocol/types";

afterEach(cleanup);
const task: Dst1Task = { i: "ReminderTask0001", n: "提醒", r: 1, l: "2026-10-02T22:00", h: [60, 0], a: "2026-10-02T21:00" };
const batch = (value: Dst1Task) => ({ v: 1 as const, b: "CustomBatch00001", t: [value] });

it("preserves both reminder types across editor models and transport", () => {
  const draft = { ...createTemporaryDraftTask(), ...fieldsFromDst1(task), taskId: task.i };
  expect(draftTaskToDst1(draft)).toEqual(task);
  expect(decodeDst1(encodeDst1(batch(task)).envelope).batch.t![0]).toEqual(task);
  expect(() => validateDst1Batch(batch({ ...task, l: null, h: undefined }))).not.toThrow();
});

it("validates task day midnight boundaries and effective exceptions", () => {
  expect(reminderForTaskDay("2026-10-02", "02:00")).toBe("2026-10-03T02:00");
  expect(reminderForTaskDay("2026-10-02", "04:00")).toBe("2026-10-02T04:00");
  const recurring = { ...task, h: undefined, l: undefined, x: { f: 1 as const }, a: "02:00" };
  expect(() => validateDst1Batch(batch(recurring))).not.toThrow();
  expect(() => validateDst1Batch({ ...batch(recurring), sv: 1, e: [{ i: task.i, y: "2026-10-02", l: "2026-10-02T23:00" }] })).toThrow(/不能晚于/);
  expect(() => validateDst1Batch({ ...batch(recurring), sv: 1, e: [{ i: task.i, y: "2026-10-02", l: "2026-10-02T23:00", a: null }] })).not.toThrow();
  for (const value of [{ ...task, a: "2026-02-30T21:00" }, { ...task, a: "2026-10-02T22:01" },
    { ...recurring, a: "25:00" }, { ...recurring, x: { f: 1, t: "21:00" }, a: "02:00" }]) {
    expect(() => validateDst1Batch(batch(value as Dst1Task))).toThrow();
  }
});

it("edits one custom reminder independently and clears it on task-kind conversion", () => {
  let current: EditableTask = { ...createTemporaryDraftTask(), ...fieldsFromDst1(task), groupId: null };
  function Harness() { const [value, setValue] = useState(current); return <TaskEditor value={value} groups={[]} allowKindChange onChange={(next) => { current = next; setValue(next); }} />; }
  render(<Harness />);
  fireEvent.change(screen.getByLabelText("提醒日期时间"), { target: { value: "2026-10-02T20:30" } });
  expect(current.customReminder).toBe("2026-10-02T20:30");
  expect(current.reminders).toEqual([60, 0]);
  fireEvent.click(screen.getByRole("checkbox", { name: "自定义提醒时间" }));
  expect(current.customReminder).toBeNull();
  expect(current.reminders).toEqual([60, 0]);
  fireEvent.change(screen.getByLabelText("任务类型"), { target: { value: "recurring" } });
  fireEvent.click(screen.getByRole("checkbox", { name: "自定义提醒时间" }));
  expect(taskIssues(current)).toContain("重复任务的自定义提醒必须为 HH:mm");
  fireEvent.change(screen.getByLabelText("任务日内提醒时刻"), { target: { value: "02:00" } });
  expect(current.customReminder).toBe("02:00");
  expect(screen.getByText(/00:00～03:59/)).toBeTruthy();
});

it("overrides clears and inherits the occurrence reminder", () => {
  const record = taskRecordFromDraft({ ...createTemporaryDraftTask(), name: "提醒", recurrence: { f: 1 }, customReminder: "02:00" });
  let saved: Dst11Exception | undefined;
  const initial: Dst11Exception = { i: record.id, y: "2026-10-02", a: "2026-10-02T21:00" };
  render(<ExceptionEditor task={record} initial={initial} onSave={(value) => { saved = value; }} onCancel={() => {}} />);
  fireEvent.change(screen.getByLabelText("当天自定义提醒（留空清除）"), { target: { value: "" } });
  fireEvent.click(screen.getByText("保存并加入草稿"));
  expect(saved?.a).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "自定义提醒" }));
  fireEvent.click(screen.getByText("保存并加入草稿"));
  expect(saved).not.toHaveProperty("a");
});

it("roundtrips new backups while accepting old absent fields", async () => {
  await db.delete(); await db.open();
  const record = taskRecordFromDraft({ ...createTemporaryDraftTask(), ...fieldsFromDst1(task) });
  await db.tasks.put(record);
  const backup = await createBackup();
  expect(parseBackup(JSON.stringify(backup)).tasks[0].customReminder).toBe(task.a);
  expect(backup.minorVersion).toBe(4);
  const old = { ...backup, minorVersion: 2, tasks: backup.tasks.map(({ customReminder: _, ...rest }) => rest) };
  expect(parseBackup(JSON.stringify(old)).tasks[0].customReminder).toBeUndefined();
  expect(() => parseBackup(JSON.stringify({ ...backup, tasks: [{ ...record, customReminder: "2026-10-03T01:00" }] }))).toThrow(/不能晚于/);
});
