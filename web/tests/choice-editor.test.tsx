import "fake-indexeddb/auto";
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { StepListEditor } from "../src/components/StepListEditor";
import { TaskEditor, type EditableTask } from "../src/components/TaskEditor";
import { createTemporaryDraftTask, createDraft } from "../src/model/defaults";
import { createBackup, importBackup, parseBackup } from "../src/db/backup";
import { db } from "../src/db/database";
import { taskRecordFromDraft } from "../src/protocol/builder";
import type { Dst1Step } from "../src/protocol/types";

afterEach(cleanup);
it("retains option identities across renaming sorting and blocks deleting referenced sources", () => {
  let current: Dst1Step[] = [
    { i: "Step000000000001", n: "选择", r: 1, u: { k: 7, o: [{ i: "Option0000000001", n: "A", p: 0 }, { i: "Option0000000002", n: "B", p: 9 }, { i: "Option0000000003", n: "C", p: 3 }] } },
    { i: "Step000000000002", n: "通知", r: 1, u: { k: 6, t: "正文" }, c: { s: "Step000000000001", o: "Option0000000001" } },
  ];
  function Harness() { const [steps, setSteps] = useState(current); return <StepListEditor steps={steps} taskId="Task000000000001" onChange={(next) => { current = next; setSteps(next); }} />; }
  render(<Harness />);
  fireEvent.change(screen.getByDisplayValue("A"), { target: { value: "改名" } });
  fireEvent.click(screen.getByRole("button", { name: "上移选项 2" }));
  expect(current[1].c).toEqual({ s: "Step000000000001", o: "Option0000000001" });
  expect(current[0].u).toMatchObject({ o: [{ i: "Option0000000002" }, { i: "Option0000000001", n: "改名" }, { i: "Option0000000003" }] });
  fireEvent.click(screen.getAllByRole("button", { name: "删除选项" })[1]);
  expect(screen.getByRole("alert")).toHaveTextContent("请先修正关联条件");
  fireEvent.click(screen.getAllByRole("button", { name: /^下移$/ })[0]);
  expect(current[0].i).toBe("Step000000000001");
  fireEvent.click(screen.getAllByRole("button", { name: "删除步骤" })[0]);
  expect(current).toHaveLength(2);
});

it("authors notice text and single choice zero and custom points", () => {
  let current: EditableTask = { ...createTemporaryDraftTask(), name: "新任务", groupId: null };
  function Harness() { const [value, setValue] = useState(current); return <TaskEditor value={value} groups={[]} onChange={(next) => { current = next; setValue(next); }} />; }
  render(<Harness />);
  fireEvent.change(screen.getByDisplayValue("直接完成"), { target: { value: "6" } });
  fireEvent.change(screen.getByLabelText(/通知正文/), { target: { value: "阅读内容" } });
  expect(current.execution).toEqual({ k: 6, t: "阅读内容" });
  fireEvent.change(screen.getByDisplayValue("阅读通知并确认"), { target: { value: "7" } });
  fireEvent.change(screen.getAllByLabelText("选项积分")[1], { target: { value: "17" } });
  expect(current.execution).toMatchObject({ k: 7, o: [{ p: 0 }, { p: 17 }] });
});

it("roundtrips choice configurations and conditions in library template and draft backups", async () => {
  await db.delete(); await db.open();
  const now = new Date().toISOString();
  const steps: Dst1Step[] = [{ i: "Step000000000001", n: "选择", r: 1, u: { k: 7, o: [{ i: "Option0000000001", n: "A", p: 0 }, { i: "Option0000000002", n: "B", p: 9 }] } }, { i: "Step000000000002", n: "通知", r: 1, u: { k: 6, t: "正文" }, c: { s: "Step000000000001", o: "Option0000000001" } }];
  const fields = { ...createTemporaryDraftTask(), name: "分支", execution: { k: 5 as const }, steps };
  await db.tasks.put({ ...taskRecordFromDraft(fields), createdAt: now, updatedAt: now, lastGeneratedAt: null, version: 0 });
  const { draftItemId: _, taskId: __, source: ___, ...template } = fields;
  await db.templates.put({ ...template, id: "ChoiceTemplate01", title: "分支模板", groupId: null, createdAt: now, updatedAt: now });
  const draft = { ...createDraft(), tasks: [fields] };
  await db.drafts.put(draft);
  const parsed = parseBackup(JSON.stringify(await createBackup()));
  await db.tasks.clear(); await db.templates.clear(); await importBackup(parsed, "replace");
  expect((await db.tasks.get(fields.taskId))?.steps).toEqual(steps);
  expect((await db.templates.get("ChoiceTemplate01"))?.steps).toEqual(steps);
  expect((await db.drafts.get(draft.id))?.tasks[0].steps).toEqual(steps);
  const invalid = { ...parsed, tasks: parsed.tasks.map((task) => ({ ...task, recurrence: { f: 1 as const } })), taskExceptions: [{ id: "exception", taskId: fields.taskId, date: "2026-09-12", directive: { i: fields.taskId, y: "2026-09-12", s: [steps[1]] }, createdAt: now, updatedAt: now }] };
  expect(() => parseBackup(JSON.stringify(invalid))).toThrow();
});
