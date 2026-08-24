import { afterEach, describe, expect, it, vi } from "vitest";
import { handleRequest } from "../src/index";

const origin = "https://private.example.test";
const administrator = "manager@example.test";
const gateSecret = "test-only-management-gate-secret-with-more-than-32-bytes";
const userAgent = "gate-test-browser";

type TestEnv = Parameters<typeof handleRequest>[1];

function environment(api: (request: Request) => Response | Promise<Response> = () => new Response("api")) {
  let assetRequests = 0;
  let apiRequests = 0;
  const env = {
    MANAGEMENT_GATE_ENABLED: "true",
    MANAGEMENT_ADMIN_EMAIL: administrator,
    MANAGEMENT_GATE_SECRET: gateSecret,
    ASSETS: {
      fetch: async () => {
        assetRequests += 1;
        return new Response("protected application", { headers: { "Content-Type": "text/html" } });
      },
    },
    API: {
      fetch: async (request: Request) => {
        apiRequests += 1;
        return api(request);
      },
    },
  } as unknown as TestEnv;
  return { env, counts: () => ({ assetRequests, apiRequests }) };
}

function form(path: string, values: Record<string, string>): Request {
  return new Request(`${origin}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Origin": origin,
      "User-Agent": userAgent,
    },
    body: new URLSearchParams(values),
  });
}

async function gateCookie(env: TestEnv): Promise<string> {
  await handleRequest(form("/__gate/challenge", { email: administrator }), env);
  const verified = await handleRequest(form("/__gate/verify", { email: administrator, challengeId: "challenge-1", code: "123456" }), env);
  const combined = verified.headers.get("Set-Cookie") ?? "";
  const match = combined.match(/__Host-dst_manager_gate=([^;,\s]+)/u);
  if (!match) throw new Error("gate cookie was not issued");
  return `__Host-dst_manager_gate=${match[1]}`;
}

function successfulApi(request: Request): Response {
  const path = new URL(request.url).pathname;
  if (path === "/v1/auth/challenges") {
    return Response.json({ challengeId: "challenge-1", accepted: true }, { status: 202 });
  }
  if (path === "/v1/auth/verify") {
    return Response.json(
      { accessToken: "must-not-reach-the-browser-here", csrfToken: "also-hidden", accessExpiresAt: "2099-01-01T00:00:00Z" },
      { headers: { "Set-Cookie": "dst_refresh=refresh-token; Path=/v1/auth; HttpOnly; Secure; SameSite=Strict" } },
    );
  }
  return Response.json({ ok: true }, { headers: { "Set-Cookie": "dst_refresh=; Path=/v1/auth; Max-Age=0" } });
}

afterEach(() => {
  vi.useRealTimers();
});

describe("production management gate", () => {
  it("fails closed before touching assets or the API", async () => {
    const { env, counts } = environment();
    const response = await handleRequest(new Request(`${origin}/assets/application.js`), env);

    expect(response.status).toBe(401);
    expect(response.headers.get("X-DStationery-Gate")).toBe("required");
    expect(response.headers.get("Cache-Control")).toContain("no-store");
    expect(response.headers.get("X-Robots-Tag")).toContain("noindex");
    expect(await response.text()).toContain("受限管理入口");
    expect(counts()).toEqual({ assetRequests: 0, apiRequests: 0 });
  });

  it("does not send a challenge for an email outside the whitelist", async () => {
    const { env, counts } = environment();
    const response = await handleRequest(form("/__gate/challenge", { email: "someone@example.test" }), env);

    expect(response.status).toBe(401);
    expect(await response.text()).toContain("邮箱验证码");
    expect(counts().apiRequests).toBe(0);
  });

  it("rejects cross-origin and oversized gate submissions", async () => {
    const { env, counts } = environment();
    const crossOrigin = form("/__gate/challenge", { email: administrator });
    crossOrigin.headers.set("Origin", "https://attacker.example.test");
    const oversized = form("/__gate/challenge", { email: `${"x".repeat(5000)}@example.test` });

    expect((await handleRequest(crossOrigin, env)).status).toBe(403);
    expect((await handleRequest(oversized, env)).status).toBe(401);
    expect(counts().apiRequests).toBe(0);
  });

  it("accepts a same-origin browser form navigation with an opaque Origin", async () => {
    const { env, counts } = environment(successfulApi);
    const request = form("/__gate/challenge", { email: administrator });
    request.headers.set("Origin", "null");
    request.headers.set("Sec-Fetch-Site", "same-origin");

    const response = await handleRequest(request, env);

    expect(response.status).toBe(401);
    expect(await response.text()).toContain("邮箱验证码");
    expect(counts().apiRequests).toBe(1);
  });

  it("uses the API only for the exact normalized administrator email", async () => {
    let submitted: unknown;
    const { env, counts } = environment(async (request) => {
      submitted = await request.json();
      return Response.json({ challengeId: "challenge-1", accepted: true }, { status: 202 });
    });
    const response = await handleRequest(form("/__gate/challenge", { email: " Manager@Example.Test " }), env);

    expect(response.status).toBe(401);
    expect(submitted).toEqual({ email: administrator, purpose: "SIGN_IN" });
    expect(counts().apiRequests).toBe(1);
  });

  it("issues an HttpOnly gate cookie without exposing API tokens", async () => {
    const { env } = environment(successfulApi);
    const response = await handleRequest(form("/__gate/verify", { email: administrator, challengeId: "challenge-1", code: "123456" }), env);
    const cookies = response.headers.get("Set-Cookie") ?? "";

    expect(response.status).toBe(303);
    expect(response.headers.get("Location")).toBe("/");
    expect(cookies).toContain("dst_refresh=refresh-token");
    expect(cookies).toContain("__Host-dst_manager_gate=");
    expect(cookies).toContain("HttpOnly");
    expect(await response.text()).not.toContain("must-not-reach-the-browser-here");
  });

  it("allows protected assets and API calls only with a valid bound cookie", async () => {
    const { env, counts } = environment(successfulApi);
    const cookie = await gateCookie(env);
    const headers = { "Cookie": cookie, "User-Agent": userAgent };

    const asset = await handleRequest(new Request(`${origin}/`, { headers }), env);
    const api = await handleRequest(new Request(`${origin}/v1/auth/refresh`, { method: "POST", headers: { ...headers, "Origin": origin } }), env);

    expect(asset.status).toBe(200);
    expect(await asset.text()).toBe("protected application");
    expect(asset.headers.get("Cache-Control")).toContain("no-store");
    expect(api.status).toBe(200);
    expect(counts().assetRequests).toBe(1);
  });

  it("rejects tampered, expired, and differently bound cookies", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-24T00:00:00Z"));
    const { env } = environment(successfulApi);
    const cookie = await gateCookie(env);
    const tampered = `${cookie.slice(0, -1)}${cookie.endsWith("a") ? "b" : "a"}`;

    expect((await handleRequest(new Request(`${origin}/`, { headers: { "Cookie": tampered, "User-Agent": userAgent } }), env)).status).toBe(401);
    expect((await handleRequest(new Request(`${origin}/`, { headers: { "Cookie": cookie, "User-Agent": "another-browser" } }), env)).status).toBe(401);
    vi.setSystemTime(new Date("2026-08-24T04:00:01Z"));
    expect((await handleRequest(new Request(`${origin}/`, { headers: { "Cookie": cookie, "User-Agent": userAgent } }), env)).status).toBe(401);
  });

  it("clears the application gate during API logout", async () => {
    const { env } = environment(successfulApi);
    const cookie = await gateCookie(env);
    const response = await handleRequest(new Request(`${origin}/v1/auth/logout`, {
      method: "POST",
      headers: { "Cookie": cookie, "Origin": origin, "User-Agent": userAgent },
    }), env);

    expect(response.status).toBe(200);
    expect(response.headers.get("Set-Cookie")).toContain("__Host-dst_manager_gate=; Path=/; Max-Age=0");
  });

  it("returns 503 and no content when production secrets are incomplete", async () => {
    const { env, counts } = environment();
    (env as unknown as { MANAGEMENT_GATE_SECRET: string }).MANAGEMENT_GATE_SECRET = "short";
    const response = await handleRequest(new Request(`${origin}/`), env);

    expect(response.status).toBe(503);
    expect(response.headers.get("X-DStationery-Gate")).toBe("misconfigured");
    expect(counts()).toEqual({ assetRequests: 0, apiRequests: 0 });
  });
});
