import { decodeBase64Url, encodeBase64Url, hmac } from "./crypto";
import { ApiError } from "./http";
import type { Env } from "./types";

interface ChangeCursor { kind: "changes"; v: 1; spaceId: string; sequence: number; }
interface SnapshotCursor { kind: "snapshot"; v: 1; spaceId: string; baseSequence: number; entityType: string; entityId: string; }
interface ExportCursor { kind: "export"; v: 1; spaceId: string; role: "ADMIN" | "EXECUTOR"; exportedAt: string; dataset: number; key: string; }
export type Cursor = ChangeCursor | SnapshotCursor | ExportCursor;

export async function encodeCursor(env: Env, cursor: Cursor): Promise<string> {
  const payload = encodeBase64Url(JSON.stringify(cursor));
  return `${payload}.${await hmac(env.AUTH_PEPPER, `cursor:${payload}`)}`;
}

export async function decodeCursor<T extends Cursor["kind"]>(env: Env, value: string, kind: T, spaceId: string): Promise<Extract<Cursor, { kind: T }>> {
  const [payload, signature, extra] = value.split(".");
  if (!payload || !signature || extra || await hmac(env.AUTH_PEPPER, `cursor:${payload}`) !== signature) throw new ApiError(400, "INVALID_REQUEST", "同步游标无效");
  let parsed: Cursor;
  try { parsed = JSON.parse(decodeBase64Url(payload)) as Cursor; }
  catch { throw new ApiError(400, "INVALID_REQUEST", "同步游标无效"); }
  if (parsed.kind !== kind || parsed.v !== 1 || parsed.spaceId !== spaceId) throw new ApiError(400, "INVALID_REQUEST", "同步游标不属于当前空间或协议");
  return parsed as Extract<Cursor, { kind: T }>;
}
