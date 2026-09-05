import { describe, expect, it } from "vitest";
import { presentExecutionResult } from "../src/connected/results";
import type { CloudEntity } from "../src/connected/types";

function entity(entityType: string, entityId: string, payload: Record<string, unknown>): CloudEntity {
  return { key: `space-1:${entityType}:${entityId}`, spaceId: "space-1", entityType, entityId, entityVersion: 1, payloadVersion: 1, payload, updatedAt: "2026-08-23T12:35:00Z" };
}

describe("connected result presentation", () => {
  it("shows an undo as pending without reusing the old information submission", () => {
    const undo = entity("execution_event", "undo-1", {
      assignmentId: "assignment-1", occurrenceKey: "occurrence-1", eventType: "COMPLETION_UNDONE",
      occurredAt: "2026-09-05T01:01:00Z", data: { status: "PENDING" },
    });
    const submission = entity("information_submission", "submission-1", {
      assignmentId: "assignment-1", occurrenceKey: "occurrence-1", content: "previous submission",
    });
    expect(presentExecutionResult(undo, [undo, submission], "Asia/Hong_Kong")).toMatchObject({
      statusLabel: "待完成", eventLabel: "执行者撤销了任务完成状态", informationContent: null,
    });
  });
  const task = entity("task", "CloudTask0000001", {
    content: { v: 1, b: "CloudBatch00001", t: [{ i: "CloudTask0000001", n: "每日体温记录", r: 1, u: { k: 3 } }] },
  });
  const occurrence = entity("occurrence", "occurrence-1", {
    assignmentId: "assignment-1",
    occurrenceKey: "occurrence-1",
    taskId: "CloudTask0000001",
    localDate: "2026-08-23",
  });

  it("shows the submitted information using user-facing labels", () => {
    const result = entity("execution_event", "event-1", {
      assignmentId: "assignment-1",
      occurrenceKey: "occurrence-1",
      taskRevision: 2,
      eventType: "RESULT_SUBMITTED",
      occurredAt: "2026-08-23T12:34:00Z",
      data: {
        status: "COMPLETED",
        taskName: "早餐后体温记录",
        taskDate: "2026-08-23",
        informationContent: "体温 36.6℃\n无不适",
      },
      reviewReason: null,
      duplicateOf: null,
    });

    const value = presentExecutionResult(result, [task, occurrence, result], "Asia/Hong_Kong");

    expect(value).toMatchObject({
      taskName: "早餐后体温记录",
      statusLabel: "已完成",
      eventLabel: "执行者提交了任务结果",
      taskDate: "2026年8月23日",
      taskRevision: 2,
      informationContent: "体温 36.6℃\n无不适",
      reviewMessage: null,
    });
    expect(JSON.stringify(value)).not.toContain("RESULT_SUBMITTED");
  });

  it("translates legacy result and review codes without exposing internal keys", () => {
    const result = entity("execution_event", "event-2", {
      assignmentId: "assignment-1",
      occurrenceKey: "occurrence-1",
      eventType: "RESULT_SUBMITTED",
      occurredAt: "2026-08-23T12:34:00Z",
      data: { status: "MISSED" },
      reviewReason: "STALE_REVISION",
      duplicateOf: "event-1",
    });

    const value = presentExecutionResult(result, [task, occurrence, result], "Asia/Hong_Kong");

    expect(value.taskName).toBe("每日体温记录");
    expect(value.statusLabel).toBe("未完成");
    expect(value.reviewMessage).toContain("较早的任务版本");
    expect(value.duplicateMessage).toContain("候选结果");
    expect(JSON.stringify(value)).not.toContain("STALE_REVISION");
    expect(JSON.stringify(value)).not.toContain("occurrence-1");
  });

  it("merges a separately backfilled Sub information submission into the original result", () => {
    const result = entity("execution_event", "event-3", {
      assignmentId: "assignment-1",
      occurrenceKey: "occurrence-1",
      eventType: "RESULT_SUBMITTED",
      occurredAt: "2026-08-23T12:34:00Z",
      data: { status: "COMPLETED" },
    });
    const submission = entity("information_submission", "submission-1", {
      assignmentId: "assignment-1",
      occurrenceKey: "occurrence-1",
      content: "旧结果补传的完整正文",
      submittedAt: "2026-08-23T12:33:00Z",
    });

    expect(presentExecutionResult(result, [task, occurrence, result, submission], "Asia/Hong_Kong").informationContent)
      .toBe("旧结果补传的完整正文");
  });
});
