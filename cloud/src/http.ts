import type { ApiErrorBody, Env } from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
    readonly retryAfterSeconds?: number,
  ) { super(message); }
}

export function requestId(request: Request): string {
  return request.headers.get("cf-ray") ?? crypto.randomUUID();
}

export function securityHeaders(env: Env, request: Request): Headers {
  const headers = new Headers({
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=()",
    "Cache-Control": "no-store",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "Vary": "Origin",
  });
  const origin = request.headers.get("Origin");
  if (origin === env.ALLOWED_ORIGIN) {
    headers.set("Access-Control-Allow-Origin", origin);
    headers.set("Access-Control-Allow-Credentials", "true");
    headers.set("Access-Control-Expose-Headers", "Retry-After, X-Request-Id");
  }
  return headers;
}

export function json(env: Env, request: Request, value: unknown, status = 200, extra?: HeadersInit): Response {
  const headers = securityHeaders(env, request);
  headers.set("Content-Type", "application/json; charset=utf-8");
  headers.set("X-Request-Id", requestId(request));
  if (extra) new Headers(extra).forEach((value, key) => headers.append(key, value));
  return new Response(JSON.stringify(value), { status, headers });
}

export function errorResponse(env: Env, request: Request, error: unknown): Response {
  const id = requestId(request);
  const api = error instanceof ApiError ? error : new ApiError(500, "INTERNAL_ERROR", "服务器暂时无法处理请求", true);
  const body: ApiErrorBody = { error: { code: api.code, message: api.message, requestId: id, retryable: api.retryable } };
  if (api.retryAfterSeconds !== undefined) body.error.retryAfterSeconds = api.retryAfterSeconds;
  const extra = api.retryAfterSeconds === undefined ? undefined : { "Retry-After": String(api.retryAfterSeconds) };
  return json(env, request, body, api.status, extra);
}

export async function readObject(request: Request, allowed: readonly string[]): Promise<Record<string, unknown>> {
  let body: unknown;
  try { body = await request.json(); } catch { throw new ApiError(400, "INVALID_REQUEST", "请求 JSON 无效"); }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new ApiError(400, "INVALID_REQUEST", "请求必须是对象");
  const record = body as Record<string, unknown>;
  const unknown = Object.keys(record).filter((key) => !allowed.includes(key));
  if (unknown.length) throw new ApiError(400, "INVALID_REQUEST", "请求包含未知字段");
  return record;
}

export function requiredString(body: Record<string, unknown>, key: string, max = 320): string {
  const value = body[key];
  if (typeof value !== "string" || value.length === 0 || value.length > max) throw new ApiError(400, "INVALID_REQUEST", `${key} 无效`);
  return value.normalize("NFC");
}

export function assertOrigin(env: Env, request: Request): void {
  const origin = request.headers.get("Origin");
  if (origin !== null && origin !== env.ALLOWED_ORIGIN) throw new ApiError(403, "FORBIDDEN", "请求来源不允许");
}
