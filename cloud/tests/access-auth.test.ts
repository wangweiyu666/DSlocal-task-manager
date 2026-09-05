import { exportJWK, generateKeyPair, SignJWT, type JWK } from "jose";
import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { testDatabase } from "./d1-test-db";
import type { Env } from "../src/types";

const origin = "https://example.invalid";
const team = "test-access.cloudflareaccess.com";
const audience = "a".repeat(64);
const email = "admin@example.invalid";
let trustedPrivateKey: CryptoKey | undefined;
let trustedJwk: JWK | undefined;

async function setup() {
  const db = await testDatabase();
  if (!trustedPrivateKey || !trustedJwk) {
    const pair = await generateKeyPair("RS256");
    trustedPrivateKey = pair.privateKey;
    trustedJwk = await exportJWK(pair.publicKey);
    trustedJwk.kid = "trusted";
  }
  const authLimit = vi.fn(async () => ({ success: true }));
  const env = {
    ...db.env, DELETION_LEDGER: { prepare: () => ({ bind: () => ({ all: async () => ({ results: [] }) }) }) }, ENVIRONMENT: "staging", ALLOWED_ORIGIN: origin, ADMIN_EMAIL: email,
    ACCESS_TEAM_DOMAIN: team, ACCESS_AUD: audience,
    API_RATE_LIMITER: { limit: vi.fn(async () => ({ success: true })) },
    AUTH_RATE_LIMITER: { limit: authLimit },
  } as unknown as Env;
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ keys: [trustedJwk] }), {
    headers: { "Content-Type": "application/json" },
  })));
  return { ...db, env, authLimit, privateKey: trustedPrivateKey, publicJwk: trustedJwk };
}

async function jwt(privateKey: CryptoKey, claims: Record<string, unknown> = {}) {
  let builder = new SignJWT({ sub: "access-user", email, ...claims })
    .setProtectedHeader({ alg: "RS256", kid: "trusted" })
    .setIssuer(`https://${team}`).setAudience(audience);
  if (claims.iat === undefined) builder = builder.setIssuedAt();
  if (claims.exp === undefined) builder = builder.setExpirationTime("10m");
  return builder.sign(privateKey);
}

function request(token?: string, headers: Record<string, string> = {}) {
  return new Request(`${origin}/v1/auth/access`, {
    method: "POST", headers: { Origin: origin, ...(token ? { "Cf-Access-Jwt-Assertion": token } : {}), ...headers },
  });
}

async function sessionCount(sqlite: { prepare: (sql: string) => any }) {
  return Number((sqlite.prepare("SELECT COUNT(*) AS count FROM device_sessions").get() as { count: number }).count);
}

afterEach(() => vi.unstubAllGlobals());

describe("Cloudflare Access session exchange", () => {
  it("accepts a real RS256 assertion, creates an unverified session, and reuses its identity cookie", async () => {
    const { env, sqlite, privateKey } = await setup();
    const token = await jwt(privateKey);
    const first = await worker.fetch(request(token), env);
    expect(first.status).toBe(200);
    expect(first.headers.get("Set-Cookie")).toMatch(/dst_refresh=.*HttpOnly/);
    const body = await first.json() as { accessToken: string; csrfToken: string };
    expect(body.accessToken).toBeTruthy();
    expect(body.csrfToken).toBeTruthy();
    const accessRow = sqlite.prepare("SELECT sensitive_verified_at FROM device_sessions WHERE account_id='ADMIN' ORDER BY rowid DESC LIMIT 1").get() as { sensitive_verified_at: unknown };
    expect(accessRow.sensitive_verified_at).toBeNull();
    const sensitive = await worker.fetch(new Request(`${origin}/v1/account/deletion-requests`, { method: "POST", headers: { Origin: origin, Authorization: `Bearer ${body.accessToken}`, "Content-Type": "application/json" }, body: JSON.stringify({ mode: "SCHEDULED" }) }), env);
    expect(sensitive.status).toBe(403);
    const exported = await worker.fetch(new Request(`${origin}/v1/spaces/00000000-0000-7000-8000-000000000001/export`, { headers: { Origin: origin, Authorization: `Bearer ${body.accessToken}`, "X-CSRF-Token": body.csrfToken } }), env);
    expect(exported.status).toBe(403);
    expect(await exported.json()).toMatchObject({ error: { code: "REAUTHENTICATION_REQUIRED" } });
    const cookie = first.headers.get("Set-Cookie")!.split(";", 1)[0];
    const second = await worker.fetch(request(token, { Cookie: cookie }), env);
    expect(second.status).toBe(200);
    expect(await sessionCount(sqlite)).toBe(3);
    const otherCookie = await worker.fetch(request(token, { Cookie: "dst_refresh=EXECUTOR" }), env);
    expect(otherCookie.status).toBe(200);
    expect(await sessionCount(sqlite)).toBe(4);
    sqlite.prepare("UPDATE device_sessions SET revoked_at='2026-09-05T00:00:00.000Z',revoke_reason='ACCOUNT_DELETION_PENDING' WHERE account_id='ADMIN'").run();
    const recovered = await worker.fetch(request(token, { Cookie: cookie }), env);
    expect(recovered.status).toBe(200);
    expect(await sessionCount(sqlite)).toBe(5);
    expect((sqlite.prepare("SELECT sensitive_verified_at FROM device_sessions WHERE account_id='ADMIN' ORDER BY rowid DESC LIMIT 1").get() as { sensitive_verified_at: unknown }).sensitive_verified_at).toBeNull();
  });

  it("rejects invalid Access assertions without creating a session", async () => {
    for (const kind of [undefined, "iss", "aud", "exp", "iat"] as const) {
    const { env, sqlite, privateKey } = await setup();
    let token: string | undefined = kind === undefined ? undefined : await jwt(privateKey);
    if (kind === "iss") token = await new SignJWT({ sub: "access-user", email }).setProtectedHeader({ alg: "RS256", kid: "trusted" }).setIssuer("https://other.cloudflareaccess.com").setAudience(audience).setIssuedAt().setExpirationTime("10m").sign(privateKey);
    if (kind === "aud") token = await new SignJWT({ sub: "access-user", email }).setProtectedHeader({ alg: "RS256", kid: "trusted" }).setIssuer(`https://${team}`).setAudience("b".repeat(64)).setIssuedAt().setExpirationTime("10m").sign(privateKey);
    if (kind === "exp") token = await jwt(privateKey, { exp: Math.floor(Date.now() / 1000) - 60 });
    if (kind === "iat") token = await jwt(privateKey, { iat: Math.floor(Date.now() / 1000) + 60 });
    const response = await worker.fetch(request(token), env);
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ error: { code: "WEB_ACCESS_REQUIRED" } });
    expect(await sessionCount(sqlite)).toBe(2);
    }
    const { env, sqlite, privateKey } = await setup();
    const missingSub = await jwt(privateKey, { sub: "" });
    const missingEmail = await jwt(privateKey, { email: undefined });
    for (const token of [missingSub, missingEmail]) {
      expect((await worker.fetch(request(token), env)).status).toBe(403);
    }
    expect(await sessionCount(sqlite)).toBe(2);
    const failed = await setup();
    const failTeam = "jwks-failure.cloudflareaccess.com";
    (failed.env as unknown as { ACCESS_TEAM_DOMAIN: string }).ACCESS_TEAM_DOMAIN = failTeam;
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("jwks unavailable"); }));
    const failedToken = await new SignJWT({ sub: "access-user", email }).setProtectedHeader({ alg: "RS256", kid: "trusted" }).setIssuer(`https://${failTeam}`).setAudience(audience).setIssuedAt().setExpirationTime("10m").sign(privateKey);
    expect((await worker.fetch(request(failedToken), failed.env)).status).toBe(403);
    expect(await sessionCount(failed.sqlite)).toBe(2);
  });

  it("rejects forged signatures, spoofed email, non-admin and inactive identities", async () => {
    const { env, sqlite, privateKey } = await setup();
    const other = await generateKeyPair("RS256");
    const forged = await jwt(other.privateKey);
    for (const token of [forged, await jwt(privateKey, { email: "other@example.invalid" })]) {
      expect((await worker.fetch(request(token), env)).status).toBe(403);
    }
    expect((await worker.fetch(request(await jwt(privateKey, { sub: "" })), env)).status).toBe(403);
    expect((await worker.fetch(request(await jwt(privateKey, { email: undefined })), env)).status).toBe(403);
    sqlite.prepare("UPDATE memberships SET role='EXECUTOR' WHERE account_id='ADMIN'").run();
    expect((await worker.fetch(request(await jwt(privateKey)), env)).status).toBe(403);
    expect(await sessionCount(sqlite)).toBe(2);
  });

  it("rejects missing and inactive account or membership identities without creating sessions", async () => {
    for (const mutation of [
      (sqlite: any) => sqlite.prepare("UPDATE memberships SET status='REMOVED' WHERE account_id='ADMIN'").run(),
      (sqlite: any) => sqlite.prepare("UPDATE spaces SET status='DELETED' WHERE id=?").run("00000000-0000-7000-8000-000000000001"),
      (sqlite: any) => { sqlite.prepare("DELETE FROM device_sessions WHERE account_id='ADMIN'").run(); sqlite.prepare("DELETE FROM memberships WHERE account_id='ADMIN'").run(); sqlite.prepare("DELETE FROM accounts WHERE id='ADMIN'").run(); },
    ]) {
      const { env, sqlite, privateKey } = await setup();
      mutation(sqlite);
      const before = await sessionCount(sqlite);
      expect((await worker.fetch(request(await jwt(privateKey)), env)).status).toBe(403);
      expect(await sessionCount(sqlite)).toBe(before);
    }
  });

  it("allows an existing admin in deletion recovery to reach the recovery session, while deleted stays denied", async () => {
    const { env, sqlite, privateKey } = await setup();
    sqlite.prepare("UPDATE accounts SET status='DELETION_PENDING' WHERE id='ADMIN'").run();
    sqlite.prepare("UPDATE spaces SET status='DELETION_PENDING' WHERE id=?").run("00000000-0000-7000-8000-000000000001");
    const pending = await worker.fetch(request(await jwt(privateKey)), env);
    expect(pending.status).toBe(200);
    const pendingBody = await pending.json() as { accessToken: string };
    const account = await worker.fetch(new Request(`${origin}/v1/account`, { headers: { Origin: origin, Authorization: `Bearer ${pendingBody.accessToken}` } }), env);
    expect(account.status).toBe(200);
    expect(await account.json()).toMatchObject({ account: { status: "DELETION_PENDING" } });
    const bootstrap = await worker.fetch(new Request(`${origin}/v1/bootstrap`, { headers: { Origin: origin, Authorization: `Bearer ${pendingBody.accessToken}` } }), env);
    expect(bootstrap.status).toBe(409);
    sqlite.prepare("UPDATE accounts SET status='DELETED' WHERE id='ADMIN'").run();
    expect((await worker.fetch(request(await jwt(privateKey)), env)).status).toBe(403);
  });

  it("requires same-origin Access requests and fails closed for local or missing configuration", async () => {
    const { env, authLimit, sqlite, privateKey } = await setup();
    expect((await worker.fetch(request(await jwt(privateKey), { Origin: "https://evil.invalid" }), env)).status).toBe(403);
    const local = { ...env, ENVIRONMENT: "local" } as Env;
    expect((await worker.fetch(request(await jwt(privateKey)), local)).status).toBe(404);
    const broken = { ...env, ACCESS_AUD: "" } as Env;
    expect((await worker.fetch(request(await jwt(privateKey)), broken)).status).toBe(503);
  });

  it("keeps Access on the guarded auth path while ordinary anonymous API remains 401", async () => {
    const { env, authLimit, sqlite, privateKey } = await setup();
    const access = await worker.fetch(request(await jwt(privateKey)), env);
    expect(access.status).toBe(200);
    const anonymous = await worker.fetch(new Request(`${origin}/v1/bootstrap`), env);
    expect(anonymous.status).toBe(401);
    authLimit.mockResolvedValue({ success: false });
    const limited = await worker.fetch(request(await jwt(privateKey), { Authorization: "Bearer fabricated" }), env);
    expect(limited.status).toBe(429);
    expect(await sessionCount(sqlite)).toBe(3);
  });
});
