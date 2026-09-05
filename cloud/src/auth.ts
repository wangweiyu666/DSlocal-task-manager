import { addSeconds, decryptReplay, encryptReplay, hmac, randomCode, randomToken, uuidV7 } from "./crypto";
import { ApiError, json, readObject, requiredString } from "./http";
import { sendCode } from "./mail";
import { accessIdentity } from "./access";
import type { Env, SessionPrincipal } from "./types";

interface SessionBundle { accessToken: string; refreshToken: string; csrfToken: string; accessExpiresAt: string; }
export const CURRENT_PRIVACY_NOTICE_VERSION = 1;

export function normalizeEmail(value: string): string {
  const normalized = value.trim().normalize("NFC").toLowerCase();
  if (normalized.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) throw new ApiError(400, "INVALID_REQUEST", "邮箱格式无效");
  return normalized;
}

function clientIp(request: Request): string { return request.headers.get("CF-Connecting-IP") ?? "0.0.0.0"; }
function nowIso(): string { return new Date().toISOString(); }

async function digest(env: Env, kind: string, value: string): Promise<string> { return hmac(env.AUTH_PEPPER, `${kind}:${value}`); }

function cookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return null;
}

function webClient(request: Request): boolean { return request.headers.get("Origin") !== null; }

function sessionResponse(env: Env, request: Request, bundle: SessionBundle, status = 200): Response {
  const body = webClient(request)
    ? { accessToken: bundle.accessToken, accessExpiresAt: bundle.accessExpiresAt, csrfToken: bundle.csrfToken }
    : bundle;
  const extra = webClient(request)
    ? { "Set-Cookie": `dst_refresh=${encodeURIComponent(bundle.refreshToken)}; Path=/v1/auth; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict` }
    : undefined;
  return json(env, request, body, status, extra);
}

export async function requestChallenge(env: Env, request: Request): Promise<Response> {
  const body = await readObject(request, ["email", "purpose"]);
  const delivery = requiredString(body, "email");
  const email = normalizeEmail(delivery);
  const purpose = body.purpose === undefined ? "SIGN_IN" : requiredString(body, "purpose", 32);
  if (!new Set(["SIGN_IN", "SENSITIVE_ACTION", "DELETE_ACCOUNT"]).has(purpose)) throw new ApiError(400, "INVALID_REQUEST", "验证码用途无效");
  const now = nowIso();
  const hourAgo = addSeconds(now, -3600);
  const dayAgo = addSeconds(now, -86400);
  const ipDigest = await digest(env, "ip", clientIp(request));
  const [emailHour, emailDay, ipHour] = await Promise.all([
    env.DB.prepare("SELECT COUNT(*) AS count FROM email_challenges WHERE email_normalized = ? AND created_at >= ?").bind(email, hourAgo).first<{ count: number }>(),
    env.DB.prepare("SELECT COUNT(*) AS count FROM email_challenges WHERE email_normalized = ? AND created_at >= ?").bind(email, dayAgo).first<{ count: number }>(),
    env.DB.prepare("SELECT COUNT(*) AS count FROM email_challenges WHERE request_ip_digest = ? AND created_at >= ?").bind(ipDigest, hourAgo).first<{ count: number }>(),
  ]);
  const latest = await env.DB.prepare("SELECT created_at FROM email_challenges WHERE email_normalized = ? ORDER BY created_at DESC LIMIT 1").bind(email).first<{ created_at: string }>();
  if ((emailHour?.count ?? 0) >= 5 || (emailDay?.count ?? 0) >= 20 || (ipHour?.count ?? 0) >= 20 || (latest && Date.now() - new Date(latest.created_at).getTime() < 60_000)) {
    throw new ApiError(429, "RATE_LIMITED", "请求过于频繁，请稍后重试", true, 60);
  }
  const id = uuidV7();
  const code = randomCode();
  await env.DB.batch([
    env.DB.prepare("UPDATE email_challenges SET consumed_at = ? WHERE email_normalized = ? AND consumed_at IS NULL").bind(now, email),
    env.DB.prepare("INSERT INTO email_challenges(id,email_normalized,email_delivery,purpose,code_digest,attempts_remaining,expires_at,request_ip_digest,created_at) VALUES (?,?,?,?,?,5,?,?,?)")
      .bind(id, email, delivery, purpose, await digest(env, `code:${id}`, code), addSeconds(now, 600), ipDigest, now),
  ]);
  const account = await env.DB.prepare("SELECT status FROM accounts WHERE email_normalized=?").bind(email).first<{ status: string }>();
  const mailPurpose = purpose === "SENSITIVE_ACTION" || purpose === "DELETE_ACCOUNT"
    ? "SENSITIVE_ACTION"
    : account?.status === "DELETION_PENDING" ? "RECOVERY" : "SIGN_IN";
  try { await sendCode(env, delivery, code, `challenge-${id}`, mailPurpose); }
  catch (error) { await env.DB.prepare("DELETE FROM email_challenges WHERE id = ?").bind(id).run(); throw error; }
  return json(env, request, { challengeId: id, accepted: true }, 202);
}

async function createSession(env: Env, accountId: string, now: string, sensitiveVerified = true): Promise<SessionBundle & { sessionId: string }> {
  const sessionId = uuidV7();
  const accessToken = randomToken();
  const refreshToken = randomToken();
  const csrfToken = randomToken(24);
  const accessExpiresAt = addSeconds(now, 900);
  await env.DB.prepare("INSERT INTO device_sessions(id,account_id,access_digest,refresh_digest,csrf_digest,created_at,last_seen_at,access_expires_at,idle_expires_at,absolute_expires_at,sensitive_verified_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
    .bind(sessionId, accountId, await digest(env, "access", accessToken), await digest(env, "refresh", refreshToken), await digest(env, "csrf", csrfToken), now, now, accessExpiresAt, addSeconds(now, 2_592_000), addSeconds(now, 7_776_000), sensitiveVerified ? now : null).run();
  return { sessionId, accessToken, refreshToken, csrfToken, accessExpiresAt };
}

export async function verifyChallenge(env: Env, request: Request): Promise<Response> {
  const body = await readObject(request, ["challengeId", "email", "code"]);
  const challengeId = requiredString(body, "challengeId", 64);
  const email = normalizeEmail(requiredString(body, "email"));
  const code = requiredString(body, "code", 6);
  if (!/^\d{6}$/.test(code)) throw new ApiError(400, "INVALID_REQUEST", "验证码格式无效");
  const challenge = await env.DB.prepare("SELECT * FROM email_challenges WHERE id = ? AND email_normalized = ?").bind(challengeId, email).first<Record<string, unknown>>();
  const now = nowIso();
  if (!challenge || challenge.consumed_at || String(challenge.expires_at) <= now || Number(challenge.attempts_remaining) <= 0) throw new ApiError(400, "CHALLENGE_INVALID", "验证码无效或已过期");
  const sensitivePrincipal = challenge.purpose === "SIGN_IN" ? null : await authenticate(env, request, true);
  if (sensitivePrincipal && (sensitivePrincipal.email !== email || sensitivePrincipal.accountStatus === "DELETED")) {
    throw new ApiError(403, "FORBIDDEN", "验证码与当前账号不匹配");
  }
  const expected = await digest(env, `code:${challengeId}`, code);
  if (expected !== challenge.code_digest) {
    await env.DB.prepare("UPDATE email_challenges SET attempts_remaining = attempts_remaining - 1 WHERE id = ?").bind(challengeId).run();
    throw new ApiError(400, "CHALLENGE_INVALID", "验证码无效或已过期");
  }
  const consumed = await env.DB.prepare("UPDATE email_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL").bind(now, challengeId).run();
  if ((consumed.meta.changes ?? 0) !== 1) throw new ApiError(400, "CHALLENGE_INVALID", "验证码无效或已过期");
  if (sensitivePrincipal) {
    await env.DB.prepare("UPDATE device_sessions SET sensitive_verified_at=?,last_seen_at=? WHERE id=?").bind(now, now, sensitivePrincipal.sessionId).run();
    return json(env, request, { sensitiveVerifiedAt: now });
  }
  let account = await env.DB.prepare("SELECT id FROM accounts WHERE email_normalized = ?").bind(email).first<{ id: string }>();
  if (!account) {
    const id = uuidV7();
    await env.DB.prepare("INSERT INTO accounts(id,email_normalized,email_delivery,created_at,verified_at) VALUES (?,?,?,?,?)").bind(id, email, String(challenge.email_delivery), now, now).run();
    account = { id };
  } else {
    await env.DB.prepare("UPDATE accounts SET verified_at = ?, email_delivery = ? WHERE id = ?").bind(now, String(challenge.email_delivery), account.id).run();
  }
  const bundle = await createSession(env, account.id, now);
  return sessionResponse(env, request, bundle);
}

export async function accessSession(env: Env, request: Request): Promise<Response> {
  const email = await accessIdentity(env, request);
  const account = await env.DB.prepare("SELECT a.id,a.status FROM accounts a JOIN memberships m ON m.account_id=a.id JOIN spaces s ON s.id=m.space_id WHERE a.email_normalized=? AND a.status IN ('ACTIVE','DELETION_PENDING') AND m.role='ADMIN' AND m.status='ACTIVE' AND s.status IN ('ACTIVE','DELETION_PENDING') LIMIT 1")
    .bind(email).first<{ id: string; status: string }>();
  if (!account) throw new ApiError(403, "ADMIN_REQUIRED", "当前身份没有可用的管理员空间");
  // Reuse only a session belonging to the verified Access identity. Never grant a role or
  // sensitive-action verification merely because the browser passed the outer gate.
  const token = cookie(request, "dst_refresh");
  // Pending accounts may enter the existing recovery screen, while business endpoints
  // keep enforcing requireAccountReady and the space state. Deleted identities stay denied.
  if (token && account.status === "ACTIVE") {
    const tokenDigest = await digest(env, "refresh", token);
    const existing = await env.DB.prepare("SELECT account_id FROM device_sessions WHERE refresh_digest=? OR previous_refresh_digest=?")
      .bind(tokenDigest, tokenDigest).first<{ account_id: string }>();
    if (existing?.account_id === account.id) {
      try { return await rotateSession(env, request, token); }
      catch (error) {
        // An old cookie can still carry a deletion revocation after the account was
        // restored elsewhere. The current account/role above and fresh Access proof
        // authorize a new session; they never unfreeze a pending account or space.
        if (!(error instanceof ApiError) || !["SESSION_EXPIRED", "SESSION_REPLAYED", "ACCOUNT_DELETION_PENDING", "SPACE_DELETION_PENDING"].includes(error.code)) throw error;
      }
    }
  }
  return sessionResponse(env, request, await createSession(env, account.id, nowIso(), false));
}

export async function refreshSession(env: Env, request: Request): Promise<Response> {
  const cookieToken = cookie(request, "dst_refresh");
  const body = cookieToken ? {} : await readObject(request, ["refreshToken"]);
  const token = cookieToken ?? (typeof body.refreshToken === "string" ? body.refreshToken : null);
  if (!token) throw new ApiError(401, "UNAUTHENTICATED", "缺少刷新令牌");
  return rotateSession(env, request, token);
}

async function rotateSession(env: Env, request: Request, token: string, retry = true): Promise<Response> {
  const tokenDigest = await digest(env, "refresh", token);
  const session = await env.DB.prepare("SELECT * FROM device_sessions WHERE refresh_digest = ? OR previous_refresh_digest = ?").bind(tokenDigest, tokenDigest).first<Record<string, unknown>>();
  const now = nowIso();
  if (session?.revoked_at && session.revoke_reason === "ACCOUNT_DELETION_PENDING") throw new ApiError(401, "ACCOUNT_DELETION_PENDING", "账号正在删除恢复期内");
  if (session?.revoked_at && session.revoke_reason === "SPACE_DELETION_PENDING") throw new ApiError(401, "SPACE_DELETION_PENDING", "空间正在删除恢复期内");
  if (!session || session.revoked_at || String(session.idle_expires_at) <= now || String(session.absolute_expires_at) <= now) throw new ApiError(401, "SESSION_EXPIRED", "会话已过期，请重新验证邮箱");
  if (session.previous_refresh_digest === tokenDigest) {
    if (String(session.previous_refresh_valid_until) >= now && session.replay_bundle_ciphertext) {
      return sessionResponse(env, request, await decryptReplay<SessionBundle>(env.AUTH_PEPPER, String(session.replay_bundle_ciphertext)));
    }
    await env.DB.prepare("UPDATE device_sessions SET revoked_at = ?, revoke_reason = 'REFRESH_REPLAY' WHERE id = ?").bind(now, session.id).run();
    throw new ApiError(401, "SESSION_REPLAYED", "检测到会话令牌重放，请重新验证邮箱");
  }
  const bundle: SessionBundle = { accessToken: randomToken(), refreshToken: randomToken(), csrfToken: randomToken(24), accessExpiresAt: addSeconds(now, 900) };
  const rotated = await env.DB.prepare("UPDATE device_sessions SET previous_refresh_digest=refresh_digest,previous_refresh_valid_until=?,replay_bundle_ciphertext=?,refresh_digest=?,access_digest=?,csrf_digest=?,last_seen_at=?,access_expires_at=?,idle_expires_at=? WHERE id=? AND refresh_digest=? AND revoked_at IS NULL AND idle_expires_at>? AND absolute_expires_at>?")
    .bind(addSeconds(now, 30), await encryptReplay(env.AUTH_PEPPER, bundle), await digest(env, "refresh", bundle.refreshToken), await digest(env, "access", bundle.accessToken), await digest(env, "csrf", bundle.csrfToken), now, bundle.accessExpiresAt, addSeconds(now, 2_592_000), session.id, tokenDigest, now, now).run();
  if ((rotated.meta.changes ?? 0) !== 1) {
    // Another request may have rotated this token while cryptography was running.
    // Re-read its replay bundle instead of returning tokens that were never stored.
    if (retry) return rotateSession(env, request, token, false);
    throw new ApiError(401, "SESSION_EXPIRED", "会话已过期，请重新验证邮箱");
  }
  return sessionResponse(env, request, bundle);
}

export async function authenticate(env: Env, request: Request, requireCsrf = false): Promise<SessionPrincipal> {
  const header = request.headers.get("Authorization");
  if (!header?.startsWith("Bearer ")) throw new ApiError(401, "UNAUTHENTICATED", "需要登录");
  const accessDigest = await digest(env, "access", header.slice(7));
  const now = nowIso();
  const row = await env.DB.prepare("SELECT s.id AS session_id,s.account_id,s.access_expires_at,s.revoked_at,s.revoke_reason,s.sensitive_verified_at,a.email_normalized,a.status AS account_status,a.privacy_notice_version FROM device_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.access_digest=?")
    .bind(accessDigest).first<Record<string, unknown>>();
  if (row?.revoked_at && row.revoke_reason === "ACCOUNT_DELETION_PENDING") throw new ApiError(401, "ACCOUNT_DELETION_PENDING", "账号正在删除恢复期内");
  if (row?.revoked_at && row.revoke_reason === "SPACE_DELETION_PENDING") throw new ApiError(401, "SPACE_DELETION_PENDING", "空间正在删除恢复期内");
  if (!row || row.revoked_at || String(row.access_expires_at) <= now) throw new ApiError(401, "SESSION_EXPIRED", "访问令牌已过期");
  if (requireCsrf && webClient(request)) {
    const csrf = request.headers.get("X-CSRF-Token");
    const session = await env.DB.prepare("SELECT csrf_digest FROM device_sessions WHERE id=?").bind(row.session_id).first<{ csrf_digest: string }>();
    if (!csrf || !session || await digest(env, "csrf", csrf) !== session.csrf_digest) throw new ApiError(403, "FORBIDDEN", "CSRF 校验失败");
  }
  return {
    accountId: String(row.account_id),
    sessionId: String(row.session_id),
    email: String(row.email_normalized),
    accountStatus: String(row.account_status) as SessionPrincipal["accountStatus"],
    privacyNoticeVersion: Number(row.privacy_notice_version),
    sensitiveVerifiedAt: row.sensitive_verified_at ? String(row.sensitive_verified_at) : null,
  };
}

export function requireAccountReady(principal: SessionPrincipal): void {
  if (principal.accountStatus === "DELETION_PENDING") throw new ApiError(409, "ACCOUNT_DELETION_PENDING", "账号正在删除恢复期内");
  if (principal.accountStatus !== "ACTIVE") throw new ApiError(401, "ACCOUNT_DELETED", "账号已删除");
  if (principal.privacyNoticeVersion < CURRENT_PRIVACY_NOTICE_VERSION) throw new ApiError(428, "PRIVACY_ACK_REQUIRED", "请先阅读并确认联网版隐私说明");
}

export function requireFreshSensitiveVerification(principal: SessionPrincipal, now = Date.now()): void {
  const verifiedAt = principal.sensitiveVerifiedAt ? Date.parse(principal.sensitiveVerifiedAt) : Number.NaN;
  if (!Number.isFinite(verifiedAt) || now - verifiedAt > 600_000) {
    throw new ApiError(403, "REAUTHENTICATION_REQUIRED", "请先完成邮箱安全验证");
  }
}

export async function logout(env: Env, request: Request): Promise<Response> {
  const principal = await authenticate(env, request, true);
  await env.DB.prepare("UPDATE device_sessions SET revoked_at=?,revoke_reason='LOGOUT' WHERE id=?").bind(nowIso(), principal.sessionId).run();
  return json(env, request, { ok: true }, 200, webClient(request) ? { "Set-Cookie": "dst_refresh=; Path=/v1/auth; Max-Age=0; HttpOnly; Secure; SameSite=Strict" } : undefined);
}
