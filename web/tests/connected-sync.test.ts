import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CloudApi } from "../src/connected/api";
import { connectedDb, entityKey, purgeSpace } from "../src/connected/db";
import { buildCloudTaskContent, editableFromCloudTask, unpackCloudTask } from "../src/connected/library";
import { queueCommand, uuidV7 } from "../src/connected/sync";
import type { EditableTask } from "../src/components/TaskEditor";
import type { GroupRecord } from "../src/model/types";
import { cloudOccurrenceKey } from "../src/protocol/cloud";

describe("connected session refresh", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it("shares one token rotation across concurrent authenticated retries", async () => {
    const tokens = {
      accessToken: "access-next",
      refreshToken: "refresh-next",
      csrfToken: "csrf-next",
      accessExpiresAt: "2026-08-23T01:00:00Z",
      refreshExpiresAt: "2026-08-30T00:00:00Z",
    };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(tokens), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new CloudApi();

    const [first, second] = await Promise.all([api.refresh(), api.refresh()]);

    expect(first).toEqual(tokens);
    expect(second).toEqual(tokens);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("connected offline outbox", () => {
  beforeEach(async () => { await connectedDb.delete(); await connectedDb.open(); });

  it("creates lexically sortable UUIDv7 command identifiers", () => {
    const first = uuidV7(1_000);
    const second = uuidV7(2_000);
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
    expect(first < second).toBe(true);
  });

  it("builds the shared deterministic occurrence key", () => {
    expect(cloudOccurrenceKey("CloudTask0000001", 3, 2, "2026-08-17T09:30")).toBe("CloudTask0000001:3:2:2026-08-17T09:30");
  });

  it("atomically stores optimistic task state with its durable command", async () => {
    const commandId = uuidV7();
    await queueCommand("space-1", {
      commandId,
      entityId: "CloudTask0000001",
      baseVersion: 0,
      createdAt: "2026-08-17T00:00:00Z",
      type: "TASK_PUBLISH",
      payload: { content: { v: 1, b: "CloudBatch00001", t: [{ i: "CloudTask0000001", n: "任务", r: 1 }] } },
    }, "task", { id: "CloudTask0000001", version: 1, status: "ACTIVE" });
    expect(await connectedDb.outbox.get(commandId)).toMatchObject({ spaceId: "space-1", attempts: 0 });
    expect(await connectedDb.entities.get(entityKey("space-1", "task", "CloudTask0000001"))).toMatchObject({ pendingCommandId: commandId, entityVersion: 1 });
  });

  it("purges only the removed or explicitly logged-out space", async () => {
    await connectedDb.entities.bulkPut([
      { key: entityKey("space-a", "task", "CloudTask0000001"), spaceId: "space-a", entityType: "task", entityId: "CloudTask0000001", entityVersion: 1, payloadVersion: 1, payload: {}, updatedAt: "2026-08-17T00:00:00Z" },
      { key: entityKey("space-b", "task", "CloudTask0000002"), spaceId: "space-b", entityType: "task", entityId: "CloudTask0000002", entityVersion: 1, payloadVersion: 1, payload: {}, updatedAt: "2026-08-17T00:00:00Z" },
    ]);
    await purgeSpace("space-a");
    expect(await connectedDb.entities.where("spaceId").equals("space-a").count()).toBe(0);
    expect(await connectedDb.entities.where("spaceId").equals("space-b").count()).toBe(1);
  });
});

describe("connected task library DST1 snapshots", () => {
  const task: EditableTask = {
    name: "每日巡检",
    required: true,
    description: "记录结果",
    taskDate: "",
    deadlineMode: "default",
    deadline: "",
    points: 5,
    order: null,
    steps: [],
    recurrence: { f: 1, t: "20:00" },
    completionMessage: "完成",
    reminders: [],
    execution: { k: 3 },
    groupId: null,
  };
  const group: GroupRecord = {
    id: "CloudGroup000001",
    name: " 日常 ",
    completeMessage: " 全部完成 ",
    incompleteMessage: " 仍需努力 ",
    order: 0,
    createdAt: "2026-08-17T00:00:00Z",
    updatedAt: "2026-08-17T00:00:00Z",
  };

  it("keeps an ungrouped cloud task at the DST1 top level", () => {
    const content = buildCloudTaskContent("CloudTask0000001", task, [group], "Asia/Hong_Kong");
    expect(content.t).toHaveLength(1);
    expect(content.g).toBeUndefined();
    expect(unpackCloudTask(content as unknown as Record<string, unknown>)?.task.i).toBe("CloudTask0000001");
  });

  it("embeds the selected group's immutable snapshot and restores it for editing", () => {
    const content = buildCloudTaskContent("CloudTask0000001", { ...task, groupId: group.id }, [group], "Asia/Hong_Kong");
    expect(content.t).toBeUndefined();
    expect(content.g?.[0]).toMatchObject({ i: group.id, n: "日常", cm: "全部完成", im: "仍需努力" });
    expect(content.g?.[0].t?.[0].i).toBe("CloudTask0000001");
    expect(editableFromCloudTask(content as unknown as Record<string, unknown>)).toMatchObject({ name: "每日巡检", groupId: group.id, points: 5 });
  });

  it("rejects ambiguous snapshots containing more than one task", () => {
    expect(unpackCloudTask({ t: [{ i: "CloudTask0000001" }, { i: "CloudTask0000002" }] })).toBeNull();
  });
});
