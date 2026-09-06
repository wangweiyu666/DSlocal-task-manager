import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const cloudWebIndex = readFileSync(resolve(process.cwd(), "../cloud-web/src/index.ts"), "utf8");
const csp = cloudWebIndex.match(/Content-Security-Policy\",\s*\"([^\"]+)\"/)?.[1];
if (!csp) throw new Error("cloud-web CSP literal was not found");

const session = { accessToken: "smoke-access-token", csrfToken: "smoke-csrf-token", accessExpiresAt: "2099-01-01T00:00:00.000Z" };
const taskEntity = {
  entityType: "task", entityId: "T000000000000001", entityVersion: 1, payloadVersion: 1,
  updatedAt: "2026-09-06T00:00:00.000Z",
  payload: { status: "ACTIVE", assignmentMode: "ALL", content: { v: 1, b: "B000000000000001", t: [{ i: "T000000000000001", n: "CSP 启动验收任务", r: 1, y: "2026-09-06" }] } },
};

test("connected production build renders under strict CSP with a mock session", async ({ page }) => {
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const securityViolations: string[] = [];
  const unknownApiRequests: string[] = [];
  await page.addInitScript(() => {
    const violations = [] as string[];
    (window as Window & { __cspViolations?: string[] }).__cspViolations = violations;
    window.addEventListener("securitypolicyviolation", (event) => violations.push(`${event.violatedDirective}:${event.blockedURI}`));
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });

  await page.route("**/*", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname !== "/" && pathname !== "/index.html") return route.continue();
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), "content-security-policy": csp } });
  });
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown;
    if (path.endsWith("/auth/access") || path.endsWith("/auth/refresh")) body = session;
    else if (path.endsWith("/account")) body = { account: { id: "AccountSmoke0001", status: "ACTIVE", privacyNoticeVersion: 1, requiredPrivacyNoticeVersion: 1, deletionDueAt: null } };
    else if (path.endsWith("/bootstrap")) body = { account: { id: "AccountSmoke0001" }, service: { mode: "NORMAL", autoSyncIntervalSeconds: null }, memberships: [{ id: "MembershipSmoke01", role: "ADMIN", status: "ACTIVE", space: { id: "SpaceSmoke00001", name: "CSP Smoke Space", timeZone: "Asia/Hong_Kong", timeZoneVersion: 1, currentSequence: 1 } }] };
    else if (path.includes("/snapshot")) body = { entities: [taskEntity], changesCursor: "smoke-cursor", hasMore: false, nextCursor: null };
    else if (path.includes("/changes")) body = { changes: [], nextCursor: "smoke-cursor", hasMore: false };
    else if (path.endsWith("/members")) body = { members: [] };
    else if (path.endsWith("/notifications")) body = { notifications: [], unreadCount: 0 };
    else if (path.endsWith("/audit")) body = { events: [] };
    else { unknownApiRequests.push(path); body = { error: { code: "UNEXPECTED_SMOKE_REQUEST" } }; }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  const firstResponse = await page.goto("/#/tasks", { waitUntil: "domcontentloaded" });
  expect(firstResponse?.headers()["content-security-policy"]).toBe(csp);
  await expect(page.getByRole("heading", { name: "任务库", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "CSP 启动验收任务", exact: true })).toBeVisible();
  securityViolations.push(...await page.evaluate(() => (window as Window & { __cspViolations?: string[] }).__cspViolations ?? []));
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.getByRole("heading", { name: "任务库", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "CSP 启动验收任务", exact: true })).toBeVisible();
  securityViolations.push(...await page.evaluate(() => (window as Window & { __cspViolations?: string[] }).__cspViolations ?? []));
  expect(pageErrors).toEqual([]);
  expect(consoleErrors).toEqual([]);
  expect(securityViolations).toEqual([]);
  expect(unknownApiRequests).toEqual([]);
});
