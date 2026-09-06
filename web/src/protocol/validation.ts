import { validateDst1Batch as validateShared } from "../../../shared/protocol/validation";
import { validate as validateSchema } from "../../../shared/protocol/schema-validator";
import type { Dst1Batch } from "./types";
export function validateDst1Batch(value: unknown): asserts value is Dst1Batch {
  validateShared(value, validateSchema);
}
