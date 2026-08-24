interface Env {
  API: Fetcher;
  ASSETS: Fetcher;
  MANAGEMENT_GATE_ENABLED?: string;
  MANAGEMENT_GATE_SECRET?: string;
  MANAGEMENT_ADMIN_EMAIL?: string;
}

const GATE_COOKIE = "__Host-dst_manager_gate";
const GATE_TTL_SECONDS = 4 * 60 * 60;
const encoder = new TextEncoder();

interface GatePayload {
  email: string;
  expiresAt: number;
  userAgentHash: string;
}

function normalizeEmail(value: string): string | null {
  const normalized = value.trim().normalize("NFC").toLowerCase();
  return normalized.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) ? normalized : null;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character] ?? character);
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/u, "");
}

function decodeBase64Url(value: string): Uint8Array | null {
  try {
    const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
    const binary = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4));
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

async function sha256(value: string): Promise<string> {
  return base64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

async function sign(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

async function verifySignature(secret: string, value: string, signature: string): Promise<boolean> {
  const bytes = decodeBase64Url(signature);
  if (!bytes) return false;
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  return crypto.subtle.verify("HMAC", key, Uint8Array.from(bytes).buffer, encoder.encode(value));
}

function cookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const separator = part.indexOf("=");
    if (separator > 0 && part.slice(0, separator).trim() === name) return part.slice(separator + 1).trim();
  }
  return null;
}

function gateConfiguration(env: Env): { email: string; secret: string } | null {
  const email = normalizeEmail(env.MANAGEMENT_ADMIN_EMAIL ?? "");
  const secret = env.MANAGEMENT_GATE_SECRET ?? "";
  return email && encoder.encode(secret).byteLength >= 32 ? { email, secret } : null;
}

async function createGateCookie(configuration: { email: string; secret: string }, request: Request): Promise<string> {
  const payload: GatePayload = {
    email: configuration.email,
    expiresAt: Math.floor(Date.now() / 1000) + GATE_TTL_SECONDS,
    userAgentHash: await sha256(request.headers.get("User-Agent") ?? ""),
  };
  const encoded = base64Url(encoder.encode(JSON.stringify(payload)));
  const signature = await sign(configuration.secret, encoded);
  return `${GATE_COOKIE}=${encoded}.${signature}; Path=/; Max-Age=${GATE_TTL_SECONDS}; HttpOnly; Secure; SameSite=Strict`;
}

function clearGateCookie(): string {
  return `${GATE_COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict`;
}

async function hasValidGate(request: Request, configuration: { email: string; secret: string }): Promise<boolean> {
  const value = cookie(request, GATE_COOKIE);
  if (!value) return false;
  const separator = value.lastIndexOf(".");
  if (separator <= 0) return false;
  const encoded = value.slice(0, separator);
  if (!await verifySignature(configuration.secret, encoded, value.slice(separator + 1))) return false;
  const bytes = decodeBase64Url(encoded);
  if (!bytes) return false;
  try {
    const payload = JSON.parse(new TextDecoder().decode(bytes)) as Partial<GatePayload>;
    const now = Math.floor(Date.now() / 1000);
    return payload.email === configuration.email
      && typeof payload.expiresAt === "number"
      && payload.expiresAt > now
      && payload.expiresAt <= now + GATE_TTL_SECONDS
      && payload.userAgentHash === await sha256(request.headers.get("User-Agent") ?? "");
  } catch {
    return false;
  }
}

function gateHeaders(): Headers {
  return new Headers({
    "Cache-Control": "no-store, max-age=0",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "Content-Type": "text/html; charset=utf-8",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=()",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-DStationery-Gate": "required",
    "X-Frame-Options": "DENY",
    "X-Robots-Tag": "noindex, nofollow, noarchive",
  });
}

function gatePage(options: { challengeId?: string; email?: string; error?: boolean } = {}, method = "GET"): Response {
  const challengeId = options.challengeId ?? "";
  const email = options.email ?? "";
  const error = options.error ? "<p class=\"error\">验证失败或已过期，请重试。</p>" : "";
  const form = challengeId
    ? `<form method="post" action="/__gate/verify"><input type="hidden" name="challengeId" value="${escapeHtml(challengeId)}"><input type="hidden" name="email" value="${escapeHtml(email)}"><label>邮箱验证码<input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required autofocus></label><button type="submit">验证并进入</button></form><a href="/">重新开始</a>`
    : `<form method="post" action="/__gate/challenge"><label>管理者邮箱<input name="email" type="email" autocomplete="email" maxlength="254" required autofocus></label><button type="submit">发送验证码</button></form>`;
  const body = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>管理入口验证</title><style>html{color-scheme:light dark;font-family:system-ui,sans-serif}body{display:grid;min-height:100vh;margin:0;place-items:center;background:#101114}main{box-sizing:border-box;width:min(92vw,25rem);padding:2rem;border:1px solid #3c4048;border-radius:1rem;background:#191b20}h1{font-size:1.35rem}p,label,input,button{display:block;width:100%;box-sizing:border-box}label{margin:1.5rem 0}.hint{color:#aeb4c0}.error{color:#ffb4ab}input,button{margin-top:.55rem;padding:.8rem;border-radius:.55rem;border:1px solid #666;background:#101114;color:inherit}button{cursor:pointer;background:#d0bcff;color:#251a3a;font-weight:700}a{display:inline-block;margin-top:1rem;color:#d0bcff}</style></head><body><main><p class="hint">受限管理入口</p><h1>需要邮箱验证</h1><p class="hint">仅授权管理者可访问。验证会话最多保持 4 小时。</p>${error}${form}</main></body></html>`;
  return new Response(method === "HEAD" ? null : body, { status: 401, headers: gateHeaders() });
}

function configurationError(method: string): Response {
  const headers = gateHeaders();
  headers.set("X-DStationery-Gate", "misconfigured");
  return new Response(method === "HEAD" ? null : "管理入口暂不可用。", { status: 503, headers });
}

function sameOrigin(request: Request, url: URL): boolean {
  const origin = request.headers.get("Origin");
  if (origin !== null && origin !== "null") return origin === url.origin;
  return request.headers.get("Sec-Fetch-Site") === "same-origin";
}

async function readForm(request: Request): Promise<URLSearchParams | null> {
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (Number.isFinite(length) && length > 4096) return null;
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) return null;
  const reader = request.body?.getReader();
  if (!reader) return new URLSearchParams();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > 4096) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return new URLSearchParams(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    return null;
  }
}

function apiRequest(request: Request, url: URL, path: string, body: Record<string, string>): Request {
  const headers = new Headers({
    "Content-Type": "application/json",
    "Origin": url.origin,
    "X-Forwarded-Host": url.host,
  });
  const connectingIp = request.headers.get("CF-Connecting-IP");
  if (connectingIp) headers.set("CF-Connecting-IP", connectingIp);
  return new Request(new URL(path, url.origin), { method: "POST", headers, body: JSON.stringify(body) });
}

async function handleChallenge(request: Request, env: Env, url: URL, configuration: { email: string; secret: string }): Promise<Response> {
  if (!sameOrigin(request, url)) return new Response("Forbidden", { status: 403, headers: gateHeaders() });
  const form = await readForm(request);
  const submitted = normalizeEmail(String(form?.get("email") ?? ""));
  if (submitted !== configuration.email) {
    return gatePage({ challengeId: `unavailable-${crypto.randomUUID()}`, email: submitted ?? "" });
  }
  const response = await env.API.fetch(apiRequest(request, url, "/v1/auth/challenges", { email: submitted, purpose: "SIGN_IN" }));
  if (!response.ok) return gatePage({ error: true });
  try {
    const body = await response.json() as { challengeId?: unknown };
    if (typeof body.challengeId !== "string" || body.challengeId.length > 64) return gatePage({ error: true });
    return gatePage({ challengeId: body.challengeId, email: submitted });
  } catch {
    return gatePage({ error: true });
  }
}

async function handleVerify(request: Request, env: Env, url: URL, configuration: { email: string; secret: string }): Promise<Response> {
  if (!sameOrigin(request, url)) return new Response("Forbidden", { status: 403, headers: gateHeaders() });
  const form = await readForm(request);
  const email = normalizeEmail(String(form?.get("email") ?? ""));
  const challengeId = String(form?.get("challengeId") ?? "");
  const code = String(form?.get("code") ?? "");
  if (email !== configuration.email || challengeId.length === 0 || challengeId.length > 64 || !/^\d{6}$/.test(code)) {
    return gatePage({ challengeId: challengeId.slice(0, 64), email: email ?? "", error: true });
  }
  const response = await env.API.fetch(apiRequest(request, url, "/v1/auth/verify", { challengeId, email, code }));
  if (!response.ok) return gatePage({ challengeId, email, error: true });
  const refreshCookie = response.headers.get("Set-Cookie");
  if (!refreshCookie?.startsWith("dst_refresh=")) return gatePage({ challengeId, email, error: true });
  const headers = new Headers({ "Cache-Control": "no-store", "Location": "/", "X-DStationery-Gate": "passed" });
  headers.append("Set-Cookie", refreshCookie);
  headers.append("Set-Cookie", await createGateCookie(configuration, request));
  return new Response(null, { status: 303, headers });
}

function protectResponse(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "private, no-store, max-age=0");
  headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("X-DStationery-Gate", "passed");
  headers.set("X-Frame-Options", "DENY");
  headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
  headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

async function proxyApi(request: Request, env: Env, url: URL): Promise<Response> {
  const incomingOrigin = request.headers.get("Origin");
  if (incomingOrigin !== null && incomingOrigin !== url.origin) {
    return new Response(JSON.stringify({ error: { code: "FORBIDDEN", message: "请求来源不允许", retryable: false } }), {
      status: 403,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
  const headers = new Headers(request.headers);
  headers.set("Origin", url.origin);
  headers.set("X-Forwarded-Host", url.host);
  const response = await env.API.fetch(new Request(request, { headers }));
  if (url.pathname === "/v1/auth/logout" && request.method === "POST") {
    const protectedHeaders = new Headers(response.headers);
    protectedHeaders.append("Set-Cookie", clearGateCookie());
    return protectResponse(new Response(response.body, { status: response.status, statusText: response.statusText, headers: protectedHeaders }));
  }
  return protectResponse(response);
}

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const gated = env.MANAGEMENT_GATE_ENABLED === "true";
  if (gated) {
    const configuration = gateConfiguration(env);
    if (!configuration) return configurationError(request.method);
    if (request.method === "POST" && url.pathname === "/__gate/challenge") return handleChallenge(request, env, url, configuration);
    if (request.method === "POST" && url.pathname === "/__gate/verify") return handleVerify(request, env, url, configuration);
    if (!await hasValidGate(request, configuration)) return gatePage({}, request.method);
  }
  if (url.pathname.startsWith("/v1/") || url.pathname === "/health") return proxyApi(request, env, url);
  const response = await env.ASSETS.fetch(request);
  return protectResponse(response);
}

export default { fetch: handleRequest } satisfies ExportedHandler<Env>;
