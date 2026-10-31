import "fake-indexeddb/auto";
import { cloudApi } from "../src/connected/api";
import { connectedDb } from "../src/connected/db";
import { replaceFromSnapshot, pullChanges } from "../src/connected/sync";
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { TaskEditor, type EditableTask } from "../src/components/TaskEditor";
import { StepListEditor } from "../src/components/StepListEditor";
import { ExceptionEditor } from "../src/components/ExceptionEditor";
import { createTemporaryDraftTask } from "../src/model/defaults";
import { encodeDst1, decodeDst1 } from "../src/protocol/dst1";
import { validateDst1Batch } from "../src/protocol/validation";
import { db } from "../src/db/database";
import { createBackup, parseBackup } from "../src/db/backup";
import { taskRecordFromDraft } from "../src/protocol/builder";
import { TemperatureChart, TemperaturePage } from "../src/connected/TemperaturePage";
import { filterTemperatures, temperatureRange, temperatureRecords, temperatureSummary } from "../src/connected/temperature";
import { presentExecutionResult } from "../src/connected/results";
import type { CloudEntity } from "../src/connected/types";

afterEach(cleanup);
function entity(entityType: string, entityId: string, payload: Record<string, unknown>, updatedAt = "2026-10-31T01:00:00Z"): CloudEntity {
  return { key: entityId, spaceId: "space", entityType, entityId, entityVersion: 1, payloadVersion: 1, payload, updatedAt };
}
function fixtures() {
  const assignment = entity("assignment", "assignment", { executorMembershipId: "alice", taskId: "task" });
  const result = entity("execution_event", "event", { assignmentId: "assignment", occurrenceKey: "once", occurredAt: "2026-10-30T16:30:00Z", eventType: "RESULT_SUBMITTED", data: { executionKind: "TEMPERATURE", status: "COMPLETED", temperatureTenthsC: 365, taskName: "晨间体温" } });
  const selection = entity("result_selection", "selection", { assignmentId: "assignment", occurrenceKey: "once", executionEventId: "event" });
  return [assignment, result, selection];
}

it("temperature protocol round-trips standalone, step and exception definitions", () => {
  const batch = { v: 1 as const, sv: 1 as const, b: "Batch00000000001", t: [{ i: "Task000000000001", n: "体温", r: 1 as const, u: { k: 8 as const } }, { i: "Task000000000002", n: "步骤", r: 1 as const, u: { k: 5 as const }, s: [{ i: "Step000000000001", n: "测量", r: 1 as const, u: { k: 8 as const } }] }], e: [{ i: "Task000000000001", y: "2026-10-31", u: { k: 8 as const } }] };
  const decoded = decodeDst1(encodeDst1(batch).envelope).batch;
  expect(decoded).toEqual(batch);
  expect(() => validateDst1Batch({ ...batch, t: [{ ...batch.t[1], u: undefined }] })).toThrow();
  expect(() => validateDst1Batch({ ...batch, t: [{ ...batch.t[0], u: { k: 88 } }] })).toThrow();
  expect(() => validateDst1Batch({ ...batch, t: [{ ...batch.t[0], u: { k: 8, v: 365 } }] })).toThrow();
});

it("temperature task selection is grouped and assigns the default name", () => {
  function Harness() { const [task, setTask] = useState<EditableTask>({ ...createTemporaryDraftTask(), groupId: null }); return <TaskEditor value={task} groups={[]} onChange={setTask} />; }
  const { container } = render(<Harness />);
  expect(container.querySelector('optgroup[label="健康记录"] option[value="8"]')).toHaveTextContent("体温询问");
  fireEvent.change(screen.getByDisplayValue("直接完成"), { target: { value: "8" } });
  expect(screen.getByRole("option", { name: "体温询问" })).toHaveProperty("selected", true);
  expect(screen.getByRole("textbox", { name: "任务名称 *" })).toHaveValue("体温询问");
});

it("temperature steps share categories and cannot nest STEPS", () => {
  const { container } = render(<StepListEditor taskId="Task000000000001" steps={[{ i: "Step000000000001", n: "测量", r: 1, u: { k: 8 } }]} onChange={() => {}} />);
  expect(screen.getByDisplayValue("体温询问")).toBeInTheDocument();
  expect(container.querySelector('optgroup[label="组合任务"]')).toBeNull();
});

it("temperature exception preserves its execution override", () => {
  const task = { ...taskRecordFromDraft(createTemporaryDraftTask()), recurrence: { f: 1 as const } };
  let saved: unknown;
  render(<ExceptionEditor task={task} initial={{ i: task.id, y: "2026-10-31", u: { k: 8 } }} onSave={(value) => { saved = value; }} onCancel={() => {}} />);
  fireEvent.click(screen.getByRole("button", { name: "保存并加入草稿" }));
  expect(saved).toMatchObject({ u: { k: 8 } });
});

it("temperature backup v4 round-trips definitions and still reads older backups", async () => {
  await db.delete(); await db.open();
  const task = taskRecordFromDraft({ ...createTemporaryDraftTask(), name: "体温", execution: { k: 8 } });
  await db.tasks.put(task);
  const backup = await createBackup();
  expect(backup.minorVersion).toBe(4);
  expect(parseBackup(JSON.stringify(backup)).tasks[0].execution).toEqual({ k: 8 });
  expect(parseBackup(JSON.stringify({ ...backup, minorVersion: 3, tasks: [] })).tasks).toEqual([]);
  expect(() => parseBackup(JSON.stringify({ ...backup, tasks: [{ ...task, execution: { k: 88 } }] }))).toThrow();
});

it("temperature statistics include only selected events and resolve adopted undo", () => {
  const entities = fixtures();
  const duplicate = { ...entities[1], entityId: "duplicate" };
  expect(temperatureRecords([...entities, duplicate])).toHaveLength(1);
  const undo = entity("execution_event", "undo", { ...entities[1].payload, eventType: "COMPLETION_UNDONE", data: { status: "PENDING" } });
  const selectedUndo = entity("result_selection", "selectedUndo", { ...entities[2].payload, executionEventId: "undo" }, "2026-10-31T02:00:00Z");
  expect(temperatureRecords([...entities, undo, selectedUndo])).toEqual([]);
  expect(temperatureRecords([...entities, duplicate, entity("result_selection", "adopted", { ...entities[2].payload, executionEventId: "duplicate" }, "2026-10-31T03:00:00Z")])[0].id).toBe("duplicate");
});

it("temperature statistics distinguish tasks and steps and exclude skipped branches", () => {
  const entities = fixtures();
  entities[1].payload.data = { executionKind: "STEPS", status: "COMPLETED", taskName: "检查", stepResults: [
    { required: true, stepId: "a", name: "早", execution: { k: 8 }, status: "CONFIRMED", temperatureTenthsC: 350 },
    { required: true, stepId: "b", name: "晚", execution: { k: 8 }, status: "CONFIRMED", temperatureTenthsC: 420 },
    { required: true, stepId: "c", execution: { k: 8 }, status: "SKIPPED", temperatureTenthsC: 365 },
    { required: true, stepId: "d", execution: { k: 8 }, status: "NOT_APPLICABLE", temperatureTenthsC: 365 },
    { required: true, stepId: "e", execution: { k: 8 }, status: "CONFIRMED", temperatureTenthsC: 365.5 },
  ] };
  const records = temperatureRecords(entities);
  expect(records).toHaveLength(2);
  expect(new Set(records.map((item) => item.seriesId)).size).toBe(2);
  expect(temperatureSummary(records)).toEqual({ count: 2, latest: 420, min: 350, max: 420, mean: 385 });
  expect(presentExecutionResult(entities[1], entities, "Asia/Hong_Kong").stepResults[0].answer).toBe("35.0℃");
  const otherAssignment = entity("assignment", "other-assignment", { executorMembershipId: "alice", taskId: "other-task" });
  const otherResult = entity("execution_event", "other-result", { ...entities[1].payload, assignmentId: "other-assignment" });
  const otherSelection = entity("result_selection", "other-selection", { assignmentId: "other-assignment", occurrenceKey: "once", executionEventId: "other-result" });
  const acrossTasks = temperatureRecords([...entities, otherAssignment, otherResult, otherSelection]);
  expect(new Set(acrossTasks.map((item) => item.seriesId)).size).toBe(4);
  expect(filterTemperatures(acrossTasks, "alice", "other-task", "2026-10-31", "2026-10-31", "Asia/Hong_Kong")).toHaveLength(2);
});

it("temperature filters use inclusive space-local calendar dates and executor boundaries", () => {
  const records = temperatureRecords(fixtures());
  expect(filterTemperatures(records, "alice", "all", "2026-10-31", "2026-10-31", "Asia/Hong_Kong")).toHaveLength(1);
  expect(filterTemperatures(records, "alice", "all", "2026-10-31", "2026-10-31", "UTC")).toHaveLength(0);
  expect(filterTemperatures(records, "bob", "all", "2026-10-30", "2026-10-31", "UTC")).toHaveLength(0);
  expect(filterTemperatures(records, "alice", "other", "2026-10-30", "2026-10-31", "UTC")).toHaveLength(0);
  expect(temperatureRange(7, "Asia/Hong_Kong", new Date("2026-10-30T16:00:00Z"))).toEqual({ start: "2026-10-25", end: "2026-10-31" });
  expect(temperatureSummary([]).latest).toBeNull();
});

it("temperature chart renders empty and single-point data with accessible inspection", () => {
  const { rerender } = render(<TemperatureChart records={[]} timeZone="Asia/Hong_Kong" />);
  expect(screen.getByText("暂无体温记录")).toBeInTheDocument();
  rerender(<TemperatureChart records={temperatureRecords(fixtures())} timeZone="Asia/Hong_Kong" />);
  const point = screen.getByRole("button", { name: /36.5℃/ });
  expect(point).toHaveAttribute("cx", "450");
  fireEvent.click(point);
  expect(screen.getByText(/10\/31.*晨间体温.*36.5℃/, { selector: "p" })).toBeInTheDocument();
});

it("temperature page defaults to the first executor and supports custom date filtering and cache status", () => {
  const entities = fixtures(); entities[1].payload.occurredAt = new Date().toISOString();
  render(<TemperaturePage entities={entities} executors={[{ id: "alice", role: "EXECUTOR", status: "ACTIVE", joinedAt: "", email: "alice@example.com" }, { id: "bob", role: "EXECUTOR", status: "ACTIVE", joinedAt: "", email: "bob@example.com" }]} timeZone="Asia/Hong_Kong" online={false} />);
  expect(screen.getByRole("combobox", { name: "执行者" })).toHaveValue("alice");
  expect(screen.getByText(/离线：显示上次同步缓存/)).toBeInTheDocument();
  fireEvent.change(screen.getByRole("combobox", { name: "执行者" }), { target: { value: "bob" } });
  expect(screen.getByText("暂无体温记录")).toBeInTheDocument();
  fireEvent.change(screen.getByRole("combobox", { name: "日期范围" }), { target: { value: "custom" } });
  expect(screen.getByLabelText("开始日期")).toBeInTheDocument();
});

it("temperature selections follow server sequence when timestamps tie", () => {
  const entities = fixtures();
  entities[2].changeSequence = 4;
  const newer = { ...entities[1], entityId: "newer", payload: { ...entities[1].payload, data: { executionKind: "TEMPERATURE", status: "COMPLETED", temperatureTenthsC: 370 } } };
  const selection = { ...entities[2], entityId: "new-selection", changeSequence: 5, payload: { ...entities[2].payload, executionEventId: "newer" } };
  expect(temperatureRecords([selection, ...entities, newer])[0].temperatureTenthsC).toBe(370);
});

it("temperature official order survives snapshot and incremental sync persistence", async () => {
  await connectedDb.delete(); await connectedDb.open();
  const records = fixtures(); records[2].changeSequence = 5;
  const snapshotMock = vi.spyOn(cloudApi, "snapshot").mockResolvedValue({ entities: records, hasMore: false, nextCursor: null, changesCursor: "cursor-5" });
  const changesMock = vi.spyOn(cloudApi, "changes").mockResolvedValue({ changes: [{ entityType: "result_selection", entityId: "selection", entityVersion: 1, payloadVersion: 1, sequence: 6, operation: "UPSERT", payload: records[2].payload, createdAt: "2026-10-31T01:00:00Z" }], hasMore: false, nextCursor: "cursor-6" });
  try {
    await replaceFromSnapshot("space");
    expect((await connectedDb.entities.where("[spaceId+entityType]").equals(["space", "result_selection"]).first())?.changeSequence).toBe(5);
    await pullChanges("space");
    expect((await connectedDb.entities.where("[spaceId+entityType]").equals(["space", "result_selection"]).first())?.changeSequence).toBe(6);
  } finally { snapshotMock.mockRestore(); changesMock.mockRestore(); }
});
