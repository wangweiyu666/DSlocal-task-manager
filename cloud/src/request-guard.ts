import { ApiError, assertOrigin } from "./http";
import type { Env } from "./types";

const publicAuthPaths = new Set(["/v1/auth/challenges", "/v1/auth/verify", "/v1/auth/refresh"]);

// Run before any D1 work. Credentials and client-supplied app IDs never bypass this limit.
export async function guardRequest(env: Env, request: Request): Promise<void> {
  assertOrigin(env, request);
  const path = new URL(request.url).pathname;
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  // Separate environments even if a namespace is accidentally reused. Do not log this key.
  const key = `${env.ENVIRONMENT}:${ip}`;
  if (!env.API_RATE_LIMITER || !env.AUTH_RATE_LIMITER) {
    throw new ApiError(503, "SERVICE_UNAVAILABLE", "请求保护暂不可用，请稍后重试", true, 60);
  }
  if (!(await env.API_RATE_LIMITER.limit({ key })).success) {
    throw new ApiError(429, "RATE_LIMITED", "请求过于频繁，请在 60 秒后重试", true, 60);
  }
  const hasBearer = /^Bearer [A-Za-z0-9_-]{1,512}$/.test(request.headers.get("Authorization") ?? "");
  if ((publicAuthPaths.has(path) || !hasBearer) && !(await env.AUTH_RATE_LIMITER.limit({ key })).success) {
    throw new ApiError(429, "RATE_LIMITED", "登录请求过于频繁，请在 60 秒后重试", true, 60);
  }
  if (request.method === "OPTIONS") return;
  if (Number(request.headers.get("Content-Length") ?? 0) > 2_000_000) {
    throw new ApiError(413, "REQUEST_TOO_LARGE", "请求体过大");
  }
  const publicRequest = (request.method === "POST" && publicAuthPaths.has(path))
    || (request.method === "GET" && (path === "/health" || (env.ENVIRONMENT === "local" && path === "/__dev/mailbox")));
  if (!publicRequest && !hasBearer) {
    throw new ApiError(401, "UNAUTHENTICATED", "需要登录");
  }
}
