export type RequiredFlag = 0 | 1;

export interface Dst1Step {
  n: string;
  r: RequiredFlag;
}

export type Dst1Execution =
  | { k: 1; a: 1 | 2; v: number }
  | { k: 2; v: number }
  | { k: 3 };

export interface Dst1Recurrence {
  f: 1 | 2;
  s?: string;
  e?: string;
  c?: number;
  w?: number[];
  t?: string | null;
}

export interface Dst1Task {
  i: string;
  n: string;
  r: RequiredFlag;
  d?: string;
  y?: string;
  l?: string | null;
  p?: number;
  o?: number;
  s?: Dst1Step[];
  x?: Dst1Recurrence;
  m?: string;
  h?: number[];
  u?: Dst1Execution;
}

export interface Dst11Exception {
  i: string;
  y: string;
  c?: 1;
  n?: string;
  r?: RequiredFlag;
  d?: string;
  l?: string | null;
  p?: number;
  o?: number | null;
  s?: Dst1Step[];
  m?: string | null;
  h?: number[];
  u?: Dst1Execution | null;
}

export interface Dst1Group {
  i: string;
  n?: string;
  cm?: string;
  im?: string;
  t?: Dst1Task[];
}

export interface Dst1Batch {
  v: 1;
  sv?: 1;
  b: string;
  d?: string;
  m?: string;
  g?: Dst1Group[];
  t?: Dst1Task[];
  z?: string[];
  e?: Dst11Exception[];
}

export type Dst1ErrorCode =
  | "INPUT_TOO_LARGE"
  | "INVALID_ENVELOPE"
  | "INVALID_CHECKSUM_FORMAT"
  | "INVALID_BASE64URL"
  | "COMPRESSED_DATA_TOO_LARGE"
  | "CHECKSUM_MISMATCH"
  | "DECOMPRESSION_FAILED"
  | "JSON_TOO_LARGE"
  | "INVALID_UTF8"
  | "INVALID_JSON"
  | "TYPE_MISMATCH"
  | "UNKNOWN_FIELD"
  | "REQUIRED_FIELD_MISSING"
  | "INVALID_VALUE"
  | "VALUE_OUT_OF_RANGE"
  | "INVALID_DATE"
  | "NON_CANONICAL_TEXT"
  | "DUPLICATE_VALUE"
  | "CONFLICTING_FIELDS"
  | "EMPTY_OPERATION";

export class Dst1ProtocolError extends Error {
  constructor(
    public readonly code: Dst1ErrorCode,
    public readonly path: string,
    message: string,
    options?: ErrorOptions
  ) {
    super(message, options);
    this.name = "Dst1ProtocolError";
  }
}
