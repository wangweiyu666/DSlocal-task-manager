import { describe, expect, it } from "vitest";
import { evaluateBranches } from "../../shared/protocol/execution";
import { validateDst1Batch } from "../src/protocol/validation";
import { encodeDst1, decodeDst1 } from "../src/protocol/dst1";
import { canonicalizeBatch } from "../src/protocol/compact";
import type { Dst1Batch, Dst1Step } from "../src/protocol/types";

const a = "Option0000000001", b = "Option0000000002";
const choice = { k: 7 as const, o: [{ i: a, n: "零分 😀", p: 0 }, { i: b, n: "九分", p: 9 }] };
const steps: Dst1Step[] = [
  { i: "Step000000000001", n: "选择", r: 1, u: choice },
  { i: "Step000000000002", n: "再选择", r: 1, u: choice, c: { s: "Step000000000001", o: b } },
  { i: "Step000000000003", n: "通知", r: 1, u: { k: 6, t: "通知正文" }, c: { s: "Step000000000002", o: a } },
];
const batch: Dst1Batch = { v: 1, b: "Batch00000000001", t: [{ i: "Task000000000001", n: "分支", r: 1, u: { k: 5 }, s: steps }] };

describe("notice and conditional choices", () => {
  it("preserves stable identities scores and conditions through compact wire roundtrip", () => {
    validateDst1Batch(batch);
    const compact = canonicalizeBatch(batch);
    expect(decodeDst1(encodeDst1(compact).envelope).batch).toEqual(compact);
    expect(compact.t![0].s).toEqual(steps);
  });
  it("distinguishes waiting inactive and cascading confirmed branches", () => {
    const answers = new Map<string, { status: string; selectedOptionId?: string }>();
    expect(evaluateBranches(steps, answers)).toEqual(["APPLICABLE", "WAITING", "WAITING"]);
    answers.set(steps[0].i!, { status: "PENDING", selectedOptionId: b });
    expect(evaluateBranches(steps, answers)[1]).toBe("WAITING");
    answers.set(steps[0].i!, { status: "CONFIRMED", selectedOptionId: b });
    answers.set(steps[1].i!, { status: "CONFIRMED", selectedOptionId: a });
    expect(evaluateBranches(steps, answers)).toEqual(["APPLICABLE", "APPLICABLE", "APPLICABLE"]);
    answers.set(steps[0].i!, { status: "SKIPPED", selectedOptionId: b });
    expect(evaluateBranches(steps, answers)).toEqual(["APPLICABLE", "NOT_APPLICABLE", "NOT_APPLICABLE"]);
    answers.set(steps[0].i!, { status: "CONFIRMED", selectedOptionId: a });
    expect(evaluateBranches(steps, answers)).toEqual(["APPLICABLE", "NOT_APPLICABLE", "NOT_APPLICABLE"]);
  });
  it("rejects forward missing cyclic and exception-invalidated references", () => {
    for (const c of [{ s: steps[2].i!, o: a }, { s: "Other00000000001", o: a }, { s: steps[0].i!, o: "Missing000000001" }, { s: steps[1].i!, o: a }]) {
      expect(() => validateDst1Batch({ ...batch, t: [{ ...batch.t![0], s: [steps[0], { ...steps[1], c }, steps[2]] }] })).toThrow();
    }
    expect(() => validateDst1Batch({ ...batch, sv: 1, e: [{ i: batch.t![0].i, y: "2026-09-12", s: [steps[1], steps[0], steps[2]] }] })).toThrow();
    expect(() => validateDst1Batch({ ...batch, t: [{ ...batch.t![0], s: steps.slice(1) }] })).toThrow();
  });
  it("enforces option and Unicode bounds and rejects unknown protocol fields", () => {
    const task = batch.t![0];
    for (const u of [{ k: 6, t: " " }, { k: 6, t: "😀".repeat(2001) }, { k: 7, o: choice.o.slice(0, 1) },
      { k: 7, o: [choice.o[0], choice.o[0]] }, { k: 7, o: [choice.o[0], { ...choice.o[1], p: 10000 }] },
      { k: 7, o: [choice.o[0], { ...choice.o[1], p: 0.5 }] }, { k: 7, o: [choice.o[0], { ...choice.o[1], n: "😀".repeat(101) }] }, { ...choice, v: 1 }]) {
      expect(() => validateDst1Batch({ ...batch, t: [{ ...task, s: undefined, u }] })).toThrow();
    }
    expect(() => validateDst1Batch({ ...batch, t: [{ ...task, s: undefined, u: { k: 6, t: "😀".repeat(2000) } }] })).not.toThrow();
  });
});
