import { afterEach, describe, expect, it, vi } from "vitest";
import { CloudApi } from "../src/connected/api";

afterEach(() => vi.unstubAllGlobals());

describe("connected Cloudflare Access session handling", () => {
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
    const session = {
      accessToken: "access", refreshToken: "refresh", csrfToken: "csrf",
      accessExpiresAt: "2026-09-05T13:00:00Z", refreshExpiresAt: "2026-09-12T13:00:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(session), {
      status: 200,
      headers: { "Content-Type": "application/json", "X-DStationery-Gate": "access" },
    })));
    const api = new CloudApi();
    await expect(api.refresh()).resolves.toEqual(session);
    expect(api.requiresAccessLogout()).toBe(true);
  });
});
