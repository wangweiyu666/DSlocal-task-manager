import { expect, it } from "vitest";
import validateSchema from "../../shared/protocol/schema-validator";
import { assertDst11Content } from "../src/sync-contract";

// Web owns the complete shared TypeScript vector suite. Check only Worker wiring here;
// contract.test.ts covers valid content, and sync-recovery.test.ts covers schema rejection.
it("rejects a schema-valid but impossible date at the Worker boundary", () => {
  const content = {
    v: 1, b: "CloudBatch000001",
    t: [{ i: "CloudTask0000001", n: "test", r: 1, y: "2026-02-30" }],
  };
  expect(validateSchema(content)).toBe(true);
  expect(() => assertDst11Content(content)).toThrowError(expect.objectContaining({ code: "INVALID_REQUEST" }));
});
