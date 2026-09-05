import Ajv2020 from "ajv/dist/2020";
import schema from "../../../docs/dst1-schema.json";
import { validateDst1Batch as validateShared } from "../../../shared/protocol/validation";
import type { Dst1Batch } from "./types";
const validateSchema = new Ajv2020({ allErrors: true, strict: false, validateFormats: false }).compile(schema);
export function validateDst1Batch(value: unknown): asserts value is Dst1Batch {
  validateShared(value, validateSchema);
}
