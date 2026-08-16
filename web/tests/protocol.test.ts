import { describe, expect, it } from "vitest";
import minimalJson from "../../protocol-test-vectors/valid/minimal-step.json";
import counter from "../../protocol-test-vectors/valid/counter.json";
import timer from "../../protocol-test-vectors/valid/timer.json";
import information from "../../protocol-test-vectors/valid/information.json";
import daily from "../../protocol-test-vectors/valid/daily-recurrence.json";
import weekly from "../../protocol-test-vectors/valid/weekly-recurrence.json";
import reminders from "../../protocol-test-vectors/valid/reminders.json";
import clearFields from "../../protocol-test-vectors/valid/clear-fields.json";
import dst11Exceptions from "../../protocol-test-vectors/valid/dst11-occurrence-exceptions.json";
import minimalEnvelope from "../../protocol-test-vectors/valid/minimal-step.dst1?raw";
import badCrc from "../../protocol-test-vectors/invalid/bad-crc.dst1?raw";
import { decodeDst1, encodeDst1 } from "../src/protocol/dst1";
import { Dst1ProtocolError, type Dst1Batch } from "../src/protocol/types";
import { validateDst1Batch } from "../src/protocol/validation";
import manifest from "../../protocol-test-vectors/manifest.json";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const validVectors = [minimalJson, counter, timer, information, daily, weekly, reminders, clearFields, dst11Exceptions];
const repositoryRoot = resolve(process.cwd(), "..");
const invalidJsonCases = manifest.cases.filter((item) => item.source.kind === "json" && item.spec.result === "ERROR");

describe("DST1 v1 shared contract", () => {
  it.each(validVectors)("accepts every committed valid vector", (vector) => {
    expect(() => validateDst1Batch(vector)).not.toThrow();
    const encoded = encodeDst1(vector as Dst1Batch);
    expect(decodeDst1(encoded.envelope).batch).toEqual(vector);
  });

  it("decodes the envelope produced for Android", () => {
    expect(decodeDst1(minimalEnvelope.trim()).batch).toEqual(minimalJson);
  });

  it("uses the stable checksum error", () => {
    expect(() => decodeDst1(badCrc.trim())).toThrowError(Dst1ProtocolError);
    try { decodeDst1(badCrc.trim()); } catch (error) { expect((error as Dst1ProtocolError).code).toBe("CHECKSUM_MISMATCH"); }
  });

  it("rejects a task that is both updated and cancelled", () => {
    const task = minimalJson.g[0].t[0];
    expect(() => validateDst1Batch({ ...minimalJson, z: [task.i] })).toThrowError(expect.objectContaining({ code: "CONFLICTING_FIELDS" }));
  });

  it("uses DST1.1 only when occurrence exceptions are present", () => {
    const encoded = encodeDst1(dst11Exceptions as Dst1Batch);
    expect(encoded.envelope.startsWith("DST1.1.")).toBe(true);
    expect(decodeDst1(encoded.envelope).batch).toEqual(dst11Exceptions);
    expect(encodeDst1(minimalJson as Dst1Batch).envelope.startsWith("DST1.")).toBe(true);
  });

  it("rejects duplicate occurrence targets and cancellation overrides", () => {
    const exception = dst11Exceptions.e[0];
    expect(() => validateDst1Batch({ ...dst11Exceptions, e: [exception, exception] })).toThrowError(expect.objectContaining({ code: "DUPLICATE_VALUE" }));
    expect(() => validateDst1Batch({ ...dst11Exceptions, e: [{ i: exception.i, y: exception.y, c: 1, n: "invalid" }] })).toThrowError(expect.objectContaining({ code: "CONFLICTING_FIELDS" }));
  });

  it.each(invalidJsonCases)("matches the shared error contract for $id", (testCase) => {
    if (testCase.source.kind !== "json" || testCase.spec.result !== "ERROR") throw new Error("invalid test inventory");
    const value = JSON.parse(readFileSync(`${repositoryRoot}/protocol-test-vectors/${testCase.source.path}`, "utf8"));
    try {
      validateDst1Batch(value);
      throw new Error("expected a protocol error");
    } catch (error) {
      expect(error).toBeInstanceOf(Dst1ProtocolError);
      expect((error as Dst1ProtocolError).code).toBe(testCase.spec.code);
      expect((error as Dst1ProtocolError).path).toBe(testCase.spec.path);
    }
  });
});
