import vm from "node:vm";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { exportJWK, generateKeyPair, SignJWT, type JWK } from "jose";

const origin = "https://test.example.test";
const host = "test.example.test";
const team = "team-example.cloudflareaccess.com";
const audience = "a".repeat(64);
const administrator = "manager@example.test";
const userAgent = "access-test-browser";

type PrivateKey = Awaited<ReturnType<typeof generateKeyPair>>["privateKey"];
let privateKey: PrivateKey;
let publicJwk: JWK;
type Handler = (typeof import("../src/index"))["handleRequest"];
type TestEnv = Parameters<Handler>[1];

beforeAll(async () => {
  const pair = await generateKeyPair("RS256");
  privateKey = pair.privateKey;
  publicJwk = await exportJWK(pair.publicKey);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function loadHandler(fetchJwks = true): Promise<Handler> {
  vi.resetModules();
  if (fetchJwks) vi.stubGlobal("fetch", vi.fn(async () => Response.json({ keys: [publicJwk] })));
  return (await import("../src/index")).handleRequest;
}

function environment(api: (request: Request) => Response | Promise<Response> = () => Response.json({ ok: true })) {
  let assetRequests = 0;
  let apiRequests = 0;
  const env = {
    ACCESS_REQUIRED: "true", ACCESS_TEAM_DOMAIN: team, ACCESS_AUD: audience,
    MANAGEMENT_HOST: host, MANAGEMENT_ADMIN_EMAIL: administrator,
    ASSETS: { fetch: async () => { assetRequests += 1; return new Response("protected application"); } },
    API: { fetch: async (request: Request) => { apiRequests += 1; return api(request); } },
  } as unknown as TestEnv;
  return { env, counts: () => ({ assetRequests, apiRequests }) };
}

async function token(overrides: Partial<{ issuer: string; aud: string; email: string; iat: number; exp: number; sub: string }> = {}, key: PrivateKey = privateKey) {
  const now = Math.floor(Date.now() / 1000);
  const claims = { issuer: `https://${team}`, aud: audience, email: administrator, iat: now, exp: now + 300, sub: "cf-user-1", ...overrides };
  return new SignJWT({ email: claims.email, sub: claims.sub })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" }).setIssuer(claims.issuer).setAudience(claims.aud)
    .setIssuedAt(claims.iat).setExpirationTime(claims.exp).sign(key);
}

function request(path: string, init: RequestInit = {}) {
  return new Request(`${origin}${path}`, { ...init, headers: { "Cf-Access-Jwt-Assertion": "invalid", "User-Agent": userAgent, ...(init.headers ?? {}) } });
}

describe("Cloudflare Access management gate", () => {
  it("rejects missing, spoofed, old-cookie, and retired requests before bindings", async () => {
    const handleRequest = await loadHandler();
    const { env, counts } = environment();
    const cases = [
      new Request(`${origin}/`),
      new Request(`${origin}/`, { headers: { "Cf-Access-Authenticated-User-Email": administrator } }),
      new Request(`${origin}/`, { headers: { Cookie: "__Host-dst_manager_gate=old-cookie" } }),
      request("/__gate/challenge"), request("/assets/application.js"),
      request("/v1/auth/refresh", { method: "POST" }), request("/sw.js"), request("/v1/auth/access", { method: "GET" }),
      request("/v1/auth/access/", { method: "POST" }), new Request(`${origin}/`, { method: "HEAD" }),
    ];
    for (const candidate of cases) {
      const response = await handleRequest(candidate, env);
      expect(response.status, candidate.method === "HEAD" ? "HEAD must be denied" : candidate.url).toBe(403);
      if (candidate.method === "HEAD") expect(await response.text()).toBe("");
    }
    expect(counts()).toEqual({ assetRequests: 0, apiRequests: 0 });
  });

  it("allows a valid JWT to reach assets and API with protected headers", async () => {
    const handleRequest = await loadHandler();
    const { env, counts } = environment();
    const assertion = await token();
    const asset = await handleRequest(request("/", { headers: { "Cf-Access-Jwt-Assertion": assertion } }), env);
    const api = await handleRequest(request("/v1/tasks", { headers: { "Cf-Access-Jwt-Assertion": assertion } }), env);
    expect(asset.status).toBe(200); expect(api.status).toBe(200);
    expect(asset.headers.get("Cache-Control")).toContain("no-store");
    expect(asset.headers.get("Content-Security-Policy")).toContain("default-src 'self'");
    expect(api.headers.get("Cache-Control")).toContain("no-store");
    expect(counts()).toEqual({ assetRequests: 1, apiRequests: 1 });
  });

  it("serves a verified retirement service worker for GET/HEAD and unregisters only connected caches", async () => {
    const handleRequest = await loadHandler();
    const { env } = environment();
    const assertion = await token();
    const get = await handleRequest(request("/sw.js", { headers: { "Cf-Access-Jwt-Assertion": assertion } }), env);
    expect(get.status).toBe(200);
    expect(get.headers.get("Content-Type")).toContain("application/javascript");
    expect(get.headers.get("Cache-Control")).toContain("no-store");
    expect(get.headers.get("Service-Worker-Allowed")).toBe("/");
    const head = await handleRequest(request("/sw.js", { method: "HEAD", headers: { "Cf-Access-Jwt-Assertion": assertion } }), env);
    expect(head.status).toBe(200);
    expect(await head.text()).toBe("");

    const listeners = new Map<string, (event: { waitUntil: (promise: Promise<unknown>) => void }) => void>();
    const skipWaiting = vi.fn(async () => {});
    const claim = vi.fn(async () => {});
    const unregister = vi.fn(async () => true);
    const names = ["dstationery-connected-precache-v1", "dstationery-offline-precache-v1", "other-cache"];
    const deleted: string[] = [];
    const context = {
      self: {
        addEventListener: (name: string, listener: (event: { waitUntil: (promise: Promise<unknown>) => void }) => void) => listeners.set(name, listener),
        skipWaiting, clients: { claim }, registration: { unregister },
      },
      caches: { keys: async () => names, delete: async (name: string) => { deleted.push(name); return true; } },
    };
    vm.runInNewContext(await get.text(), context);
    expect(listeners.has("fetch")).toBe(false);
    let install!: Promise<unknown>, activate!: Promise<unknown>;
    listeners.get("install")!({ waitUntil: (promise) => { install = promise; } });
    await install;
    listeners.get("activate")!({ waitUntil: (promise) => { activate = promise; } });
    await activate;
    expect(skipWaiting).toHaveBeenCalledOnce();
    expect(claim).toHaveBeenCalledOnce();
    expect(unregister).toHaveBeenCalledOnce();
    expect(deleted).toEqual(["dstationery-connected-precache-v1"]);
  });

  it("rejects wrong audience, issuer, time, identity, missing claim, algorithm, and signature", async () => {
    const handleRequest = await loadHandler();
    const { env, counts } = environment();
    const now = Math.floor(Date.now() / 1000);
    const missingClaim = await new SignJWT({ sub: "cf-user-1" }).setProtectedHeader({ alg: "RS256" }).setIssuer(`https://${team}`).setAudience(audience).setIssuedAt(now).setExpirationTime(now + 300).sign(privateKey);
    const otherPair = await generateKeyPair("RS256");
    const values = await Promise.all([
      token({ aud: "b".repeat(64) }), token({ issuer: "https://other.cloudflareaccess.com" }),
      token({ exp: now - 10 }), token({ iat: now + 60 }), token({ email: "other@example.test" }), missingClaim,
    ]);
    const hsParts = (await token()).split(".");
    hsParts[0] = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
    values.push(hsParts.join("."));
    const badSignatureParts = (await token()).split(".");
    badSignatureParts[2] = `${badSignatureParts[2].startsWith("a") ? "b" : "a"}${badSignatureParts[2].slice(1)}`;
    values.push(badSignatureParts.join("."));
    values.push(await token({}, otherPair.privateKey));
    const noneParts = (await token()).split(".");
    noneParts[0] = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
    noneParts[2] = "";
    values.push(noneParts.join("."));
    for (const [index, assertion] of values.entries()) expect((await handleRequest(request("/", { headers: { "Cf-Access-Jwt-Assertion": assertion } }), env)).status, `invalid token case ${index}`).toBe(403);
    expect((await handleRequest(request("/", { headers: { "Cf-Access-Jwt-Assertion": await token() } }), env)).status).toBe(200);
    expect(counts()).toEqual({ assetRequests: 1, apiRequests: 0 });
  });

  it("fails closed for invalid configuration and wrong host while allowing local loopback", async () => {
    const handleRequest = await loadHandler();
    for (const field of ["ACCESS_TEAM_DOMAIN", "ACCESS_AUD", "MANAGEMENT_HOST", "MANAGEMENT_ADMIN_EMAIL"]) {
      const configured = environment();
      (configured.env as unknown as Record<string, string>)[field] = "";
      expect((await handleRequest(request("/"), configured.env)).status, field).toBe(503);
    }
    const publicEnv = environment();
    (publicEnv.env as unknown as { ACCESS_REQUIRED: string }).ACCESS_REQUIRED = "false";
    expect((await handleRequest(new Request("https://public.example.test/"), publicEnv.env)).status).toBe(503);
    const wrongHost = new Request("https://other.example.test/", { headers: { "Cf-Access-Jwt-Assertion": await token() } });
    expect((await handleRequest(wrongHost, environment().env)).status).toBe(404);
    const local = environment();
    (local.env as unknown as { ACCESS_REQUIRED: string }).ACCESS_REQUIRED = "false";
    expect((await handleRequest(new Request("http://127.0.0.1/"), local.env)).status).toBe(200);
  });

  it("enforces origin rules and strips Access credentials while preserving API session headers", async () => {
    const handleRequest = await loadHandler();
    let forwarded: Headers | undefined;
    let forwardedPath: string | undefined;
    const { env } = environment((request) => { forwarded = request.headers; forwardedPath = new URL(request.url).pathname; return Response.json({ ok: true }); });
    const assertion = await token();
    const common = { "Cf-Access-Jwt-Assertion": assertion, "Cf-Access-Authenticated-User-Email": administrator, "Cf-Access-Client-Id": "id", "Cf-Access-Client-Secret": "secret", Cookie: "CF_Authorization=x; dst_refresh=r; __Host-dst_manager_gate=old", Authorization: "Bearer app-token", "X-CSRF-Token": "csrf", "CF-Connecting-IP": "192.0.2.1" };
    expect((await handleRequest(request("/v1/auth/logout", { method: "POST", headers: { ...common, Origin: "https://attacker.example.test" } }), env)).status).toBe(403);
    expect((await handleRequest(request("/v1/auth/logout", { method: "POST", headers: common }), env)).status).toBe(403);
    expect((await handleRequest(request("/v1/auth/logout", { method: "POST", headers: { ...common, Origin: origin } }), env)).status).toBe(200);
    expect(forwarded?.get("Authorization")).toBe("Bearer app-token");
    expect(forwarded?.get("X-CSRF-Token")).toBe("csrf");
    expect(forwarded?.get("CF-Connecting-IP")).toBe("192.0.2.1");
    expect(forwarded?.get("Cf-Access-Jwt-Assertion")).toBeNull();
    expect(forwarded?.get("Cf-Access-Authenticated-User-Email")).toBeNull();
    expect(forwarded?.get("Cf-Access-Client-Id")).toBeNull();
    expect(forwarded?.get("Cf-Access-Client-Secret")).toBeNull();
    expect(forwarded?.get("Cookie")).toContain("dst_refresh=r");
    expect(forwarded?.get("Cookie")).not.toContain("CF_Authorization");
    expect(forwarded?.get("Cookie")).not.toContain("__Host-dst_manager_gate");
    const accessResponse = await handleRequest(request("/v1/auth/access", { method: "POST", headers: { "Cf-Access-Jwt-Assertion": assertion, Origin: origin } }), env);
    expect(accessResponse.status).toBe(200);
    expect(forwardedPath).toBe("/v1/auth/access");
    expect(forwarded?.get("Cf-Access-Jwt-Assertion")).toBe(assertion);
  });

  it("rejects when the Access JWKS cannot be fetched without touching bindings", async () => {
    const handleRequest = await loadHandler(false);
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("jwks unavailable"); }));
    const { env, counts } = environment();
    expect((await handleRequest(request("/", { headers: { "Cf-Access-Jwt-Assertion": await token() } }), env)).status).toBe(403);
    expect(counts()).toEqual({ assetRequests: 0, apiRequests: 0 });
  });
});
