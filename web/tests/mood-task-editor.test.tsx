import "fake-indexeddb/auto";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TaskEditor, type EditableTask } from "../src/components/TaskEditor";
import { ExceptionEditor } from "../src/components/ExceptionEditor";
import { createTemporaryDraftTask } from "../src/model/defaults";
import { createBackup, importBackup, parseBackup } from "../src/db/backup";
import { db } from "../src/db/database";
import { taskRecordFromDraft } from "../src/protocol/builder";
import { useState } from "react";

describe("mood task editor and backup", () => {
  beforeEach(async () => { await db.delete(); await db.open(); });
  afterEach(() => cleanup());

  it("assigns the Chinese default name when selecting mood for a blank task", () => {
    const task = { ...createTemporaryDraftTask(), groupId: null } satisfies EditableTask;
    function Harness() {
      const [current, setCurrent] = useState<EditableTask>(task);
      return <TaskEditor value={current} groups={[]} onChange={setCurrent} />;
    }
    render(<Harness />);

    fireEvent.change(screen.getByDisplayValue("直接完成"), { target: { value: "4" } });

    expect(screen.getByDisplayValue("今天的心情怎么样")).toBeInTheDocument();
    expect(screen.getByDisplayValue("心情记录")).toBeInTheDocument();
  });

  it("retains a custom name when changing the execution type to mood", () => {
    const task = { ...createTemporaryDraftTask(), name: "晚间复盘", groupId: null } satisfies EditableTask;
    function Harness() {
      const [current, setCurrent] = useState<EditableTask>(task);
      return <TaskEditor value={current} groups={[]} onChange={setCurrent} />;
    }
    render(<Harness />);

    fireEvent.change(screen.getByDisplayValue("直接完成"), { target: { value: "4" } });

    expect(screen.getByDisplayValue("晚间复盘")).toBeInTheDocument();
    expect(screen.getByDisplayValue("心情记录")).toBeInTheDocument();
  });

  it("round-trips mood execution in task and template backup records", async () => {
    const now = new Date().toISOString();
    const fields = { ...createTemporaryDraftTask(), name: "心情记录", execution: { k: 4 as const } };
    await db.tasks.put({ ...taskRecordFromDraft(fields), createdAt: now, updatedAt: now, lastGeneratedAt: null, version: 0 });
    const { draftItemId: _draftItemId, taskId: _taskId, source: _source, ...templateFields } = fields;
    await db.templates.put({ ...templateFields, id: "MoodTemplate0001", title: "心情模板", groupId: null, createdAt: now, updatedAt: now });

    const parsed = parseBackup(JSON.stringify(await createBackup()));
    await db.tasks.clear();
    await db.templates.clear();
    await importBackup(parsed, "replace");

    expect((await db.tasks.get(fields.taskId))?.execution).toEqual({ k: 4 });
    expect((await db.templates.get("MoodTemplate0001"))?.execution).toEqual({ k: 4 });
  });

  it("preserves an existing mood exception when saving", () => {
    const task = moodTask();
    let saved: unknown;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07", u: { k: 4 } }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);

    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));

    expect(saved).toMatchObject({ i: task.id, y: "2026-09-07", u: { k: 4 } });
  });

  it("allows an exception execution override to be changed to mood", () => {
    const task = { ...moodTask(), execution: null };
    let saved: unknown;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07", u: null }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);

    fireEvent.change(screen.getByDisplayValue("普通完成"), { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));

    expect(saved).toMatchObject({ i: task.id, y: "2026-09-07", u: { k: 4 } });
  });

  it("converts a legacy timer exception to STEPS with its root requirement preserved", () => {
    const task = { ...moodTask(), execution: { k: 2 as const, v: 30 }, steps: [{ n: "旧步骤", r: 1 as const }] };
    let saved: any;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07" }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);

    fireEvent.click(screen.getByRole("button", { name: "步骤" }));
    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));

    expect(saved.u).toEqual({ k: 5 });
    expect(saved.s).toHaveLength(2);
    expect(saved.s.some((step: any) => step.u?.k === 2 && step.u.v === 30)).toBe(true);
    expect(saved.s.filter((step: any) => step.u?.k === 2 && step.u.v === 30)).toHaveLength(1);
    expect(new Set(saved.s.map((step: any) => step.i)).size).toBe(2);
  });

  it("saves an explicit empty step override as normal execution after deleting every step", () => {
    const task = { ...moodTask(), execution: { k: 2 as const, v: 30 }, steps: [{ n: "旧步骤", r: 1 as const }] };
    let saved: any;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07" }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);
    fireEvent.click(screen.getByRole("button", { name: "步骤" }));
    for (const button of screen.getAllByRole("button", { name: "删除步骤" })) fireEvent.click(button);
    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));
    expect(saved).toMatchObject({ u: null, s: [] });
  });

  it("requires explicit empty steps when an exception cuts out of STEPS", () => {
    const task = { ...moodTask(), steps: [{ i: "Step000000000001", n: "一步", r: 1 as const }], execution: { k: 5 as const } };
    let saved: unknown;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07", u: { k: 2, v: 10 }, s: [] }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);

    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));
    expect(saved).toMatchObject({ u: { k: 2, v: 10 }, s: [] });
  });

  it("restores normal execution when an empty STEPS exception is saved", () => {
    const task = { ...moodTask(), steps: [], execution: null };
    let saved: any;
    render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-09-07", u: { k: 5 }, s: [] }} onSave={(value) => { saved = value; }} onCancel={() => undefined} />);

    fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));
    expect(saved).toMatchObject({ u: null, s: [] });
  });
});

function moodTask() {
  const now = new Date().toISOString();
  return {
    id: "MoodWebTask00001",
    name: "每日心情",
    required: true,
    description: "",
    taskDate: "",
    deadlineMode: "default" as const,
    deadline: "",
    points: 7,
    order: null,
    steps: [],
    recurrence: { f: 1 as const },
    completionMessage: "",
    reminders: [],
    execution: { k: 4 as const },
    groupId: null,
    createdAt: now,
    updatedAt: now,
    lastGeneratedAt: null,
    version: 1,
  };
}
