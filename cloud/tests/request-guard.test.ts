import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import type { Env } from "../src/types";

afterEach(() => vi.restoreAllMocks());

function environment() {
  vi.spyOn(console, "log").mockImplementation(() => {});
  const prepare = vi.fn(() => { throw new Error("Unexpected database access"); });
  const apiLimit = vi.fn(async (_input: { key: string }) => ({ success: true }));
  const authLimit = vi.fn(async (_input: { key: string }) => ({ success: true }));
  const env = { ENVIRONMENT: "staging", ALLOWED_ORIGIN: "https://example.invalid",
    DB: { prepare }, DELETION_LEDGER: { prepare },
    API_RATE_LIMITER: { limit: apiLimit }, AUTH_RATE_LIMITER: { limit: authLimit },
  } as unknown as Env;
  return { env, prepare, apiLimit, authLimit };
}

describe("request protection before database access", () => {
  it("rejects anonymous business access and serves CORS preflight without reading D1", async () => {
    const { env, prepare } = environment();
    const response = await worker.fetch(new Request("https://example.invalid/v1/bootstrap"), env);
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ error: { code: "UNAUTHENTICATED" } });
    expect((await worker.fetch(new Request("https://example.invalid/v1/bootstrap", {
      method: "OPTIONS", headers: { Origin: env.ALLOWED_ORIGIN },
    }), env)).status).toBe(204);
    expect(prepare).not.toHaveBeenCalled();
  });

  it("blocks excessive login attempts before parsing JSON, even with a claimed app identity or bearer", async () => {
    const { env, prepare, authLimit } = environment();
    authLimit.mockResolvedValue({ success: false });
    const request = new Request("https://example.invalid/v1/auth/verify", { method: "POST", headers: {
      Origin: env.ALLOWED_ORIGIN, "CF-Connecting-IP": "192.0.2.1", "User-Agent": "DStationery",
      Authorization: "Bearer fabricated", "X-App-Id": "com.ds.localtaskmanager",
    }, body: "invalid JSON" });
    const parse = vi.spyOn(request, "json");
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(response.headers.get("Access-Control-Expose-Headers")).toContain("Retry-After");
    expect(await response.json()).toMatchObject({ error: { code: "RATE_LIMITED", retryAfterSeconds: 60 } });
    expect(authLimit).toHaveBeenCalledWith({ key: "staging:192.0.2.1" });
    expect(parse).not.toHaveBeenCalled();
    expect(prepare).not.toHaveBeenCalled();
  });

  it("keeps the all-request cap when credentials and routes change", async () => {
    const { env, prepare, apiLimit } = environment();
    apiLimit.mockResolvedValue({ success: false });
    for (const path of ["/v1/bootstrap", "/health", "/unknown"]) {
      const response = await worker.fetch(new Request(`https://example.invalid${path}`, {
        headers: { "CF-Connecting-IP": "192.0.2.2", Authorization: `Bearer fake-${path.length}` },
      }), env);
      expect(response.status).toBe(429);
    }
    expect(apiLimit.mock.calls.map(([value]) => value.key)).toEqual(Array(3).fill("staging:192.0.2.2"));
    expect(prepare).not.toHaveBeenCalled();
  });

  it("fails closed if a limiter is not configured", async () => {
    const { env, prepare } = environment();
    delete (env as Partial<Env>).AUTH_RATE_LIMITER;
    const response = await worker.fetch(new Request("https://example.invalid/health"), env);
    expect(response.status).toBe(503);
    expect(prepare).not.toHaveBeenCalled();
  });
});
