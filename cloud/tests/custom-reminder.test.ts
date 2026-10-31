import { expect, it } from "vitest";
import { assertDst11Content } from "../src/sync-contract";

it("accepts coexisting custom reminders and validates effective occurrence overrides", () => {
  const task = { i: "ReminderTask0001", n: "提醒", r: 1, l: "2026-10-02T22:00", a: "2026-10-02T21:00", h: [60] };
  const batch = { v: 1, b: "CustomBatch00001", t: [task] };
  expect(assertDst11Content(batch)).toEqual(batch);
  for (const a of ["2026-02-30T21:00", "2026-10-02T22:01", "21:00"]) {
    expect(() => assertDst11Content({ ...batch, t: [{ ...task, a }] })).toThrow();
  }
  const recurring = { v: 1, sv: 1, b: batch.b, t: [{ i: task.i, n: task.n, r: 1, x: { f: 1 }, a: "02:00" }],
    e: [{ i: task.i, y: "2026-10-02", l: "2026-10-02T23:00" }] };
  expect(() => assertDst11Content(recurring)).toThrow();
  expect(() => assertDst11Content({ ...recurring, e: [{ ...recurring.e[0], a: null }] })).not.toThrow();
});
