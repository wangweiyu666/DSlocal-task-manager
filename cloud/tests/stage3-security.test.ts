import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { CURRENT_PRIVACY_NOTICE_VERSION, requireAccountReady, requireFreshSensitiveVerification } from "../src/auth";
import { ApiError } from "../src/http";
import type { SessionPrincipal } from "../src/types";

function principal(overrides: Partial<SessionPrincipal> = {}): SessionPrincipal {
  return {
    accountId: "00000000-0000-7000-8000-000000000001",
    sessionId: "00000000-0000-7000-8000-000000000002",
    email: "person@example.com",
    accountStatus: "ACTIVE",
    privacyNoticeVersion: CURRENT_PRIVACY_NOTICE_VERSION,
    sensitiveVerifiedAt: "2026-08-24T04:00:00.000Z",
    ...overrides,
  };
}

describe("stage 3 account gates", () => {
  it("blocks business access until the current privacy notice is acknowledged", () => {
    expect(() => requireAccountReady(principal({ privacyNoticeVersion: 0 }))).toThrowError(ApiError);
    try { requireAccountReady(principal({ privacyNoticeVersion: 0 })); }
    catch (error) { expect((error as ApiError).code).toBe("PRIVACY_ACK_REQUIRED"); }
  });

  it("freezes deletion-pending accounts", () => {
    try { requireAccountReady(principal({ accountStatus: "DELETION_PENDING" })); }
    catch (error) { expect((error as ApiError).code).toBe("ACCOUNT_DELETION_PENDING"); }
  });

  it("requires sensitive verification within ten minutes", () => {
    expect(() => requireFreshSensitiveVerification(principal(), Date.parse("2026-08-24T04:09:59.000Z"))).not.toThrow();
    try { requireFreshSensitiveVerification(principal(), Date.parse("2026-08-24T04:10:01.000Z")); }
    catch (error) { expect((error as ApiError).code).toBe("REAUTHENTICATION_REQUIRED"); }
  });
});

describe("stage 3 log allowlist", () => {
  it("does not emit exception messages, stacks, request bodies, tokens or email fields", async () => {
    const source = await readFile(new URL("../src/index.ts", import.meta.url), "utf8");
    for (const forbidden of ["errorMessage", "error.stack", "request.body", "accessToken", "refreshToken", "email:"]) {
      expect(source).not.toContain(forbidden);
    }
    expect(source).toContain('return "unmatched"');
    expect(source).toContain("route: requestRoute");
    expect(source).not.toContain("path, status:");
    expect(source).toContain('event: "http_request"');
    expect(source).toContain('event: "unexpected_error"');
  });
});
