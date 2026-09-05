import { afterEach, describe, expect, it, vi } from "vitest";
import { CloudApi } from "../src/connected/api";

afterEach(() => vi.unstubAllGlobals());

describe("connected Cloudflare Access session handling", () => {
  const session = {
    accessToken: "access", refreshToken: "refresh", csrfToken: "csrf",
    accessExpiresAt: "2026-09-05T13:00:00Z", refreshExpiresAt: "2026-09-12T13:00:00Z",
  };

  it("does not treat an Access login HTML page as a valid session", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>Cloudflare Access</html>", {
      status: 200,
      headers: { "Content-Type": "text/html; charset=utf-8" },
    })));
    const api = new CloudApi();
    await expect(api.refresh()).rejects.toMatchObject({ code: "WEB_ACCESS_REQUIRED", status: 200 });
    expect(api.getSession()).toBeNull();
    expect(api.requiresAccessLogout()).toBe(false);
  });

  it("accepts JSON sessions and records the Access gate for logout handling", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(session), {
      status: 200,
      headers: { "Content-Type": "application/json", "X-DStationery-Gate": "access" },
    })));
    const api = new CloudApi();
    await expect(Promise.all([api.initializeSession(), api.initializeSession()])).resolves.toEqual([session, session]);
    expect(api.requiresAccessLogout()).toBe(true);
    expect((fetch as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(1);
  });

  it("initializes through Access and never falls back to refresh after an Access rejection", async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      expect(String(input)).toBe("/v1/auth/access");
      return new Response(JSON.stringify({ error: { code: "ACCESS_UNAVAILABLE", message: "denied" } }), {
        status: 404, headers: { "Content-Type": "application/json", "X-DStationery-Gate": "access" },
      });
    });
    vi.stubGlobal("fetch", fetch);
    const api = new CloudApi();
    await expect(api.initializeSession()).rejects.toMatchObject({ code: "ACCESS_UNAVAILABLE" });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("uses refresh only for the explicit local Access-unavailable response", async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === "/v1/auth/access") return new Response(JSON.stringify({ error: { code: "ACCESS_UNAVAILABLE" } }), {
        status: 404, headers: { "Content-Type": "application/json" },
      });
      return new Response(JSON.stringify(session), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetch);
    const api = new CloudApi();
    await expect(api.initializeSession()).resolves.toEqual(session);
    expect(fetch.mock.calls.map(([input]) => String(input))).toEqual(["/v1/auth/access", "/v1/auth/refresh"]);
  });

  it("re-exchanges through Access when an Access-gated refresh expires", async () => {
    let refreshCalls = 0;
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === "/v1/auth/refresh") {
        refreshCalls += 1;
        if (refreshCalls === 1) return new Response(JSON.stringify(session), {
          status: 200, headers: { "Content-Type": "application/json", "X-DStationery-Gate": "access" },
        });
        return new Response(JSON.stringify({ error: { code: "SESSION_EXPIRED" } }), {
          status: 401, headers: { "Content-Type": "application/json" },
        });
      }
      expect(path).toBe("/v1/auth/access");
      return new Response(JSON.stringify(session), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetch);
    const api = new CloudApi();
    await api.refresh();
    await expect(api.refresh()).resolves.toEqual(session);
    expect(fetch.mock.calls.map(([input]) => String(input))).toEqual([
      "/v1/auth/refresh", "/v1/auth/refresh", "/v1/auth/access",
    ]);
  });
});
