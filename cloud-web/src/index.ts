import { createRemoteJWKSet, jwtVerify } from "jose";

interface Env {
  API: Fetcher;
  ASSETS: Fetcher;
  ACCESS_REQUIRED?: string;
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;
  MANAGEMENT_HOST?: string;
  MANAGEMENT_ADMIN_EMAIL?: string;
}

const keySets = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

// Older connected builds registered /sw.js. Keep this retirement response available
// so those browsers can stop serving an obsolete login shell from their precache.
const retireConnectedWorker = `self.addEventListener("install", event => event.waitUntil(self.skipWaiting()));
self.addEventListener("activate", event => event.waitUntil((async () => {
  await self.clients.claim();
  for (const name of await caches.keys()) {
    if (name.startsWith("dstationery-connected-")) await caches.delete(name);
  }
  await self.registration.unregister();
})()));
`;

function normalizeEmail(value: string): string | null {
  const normalized = value.trim().normalize("NFC").toLowerCase();
  return normalized.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) ? normalized : null;
}

function protectResponse(response: Response, gate: string): Response {
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "private, no-store, max-age=0");
  headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("X-DStationery-Gate", gate);
  headers.set("X-Frame-Options", "DENY");
  headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
  headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function deny(request: Request, status: number, gate: string): Response {
  return protectResponse(new Response(request.method === "HEAD" ? null : "管理入口暂不可用，请通过授权入口重新登录。", {
    status, headers: { "Content-Type": "text/plain; charset=utf-8" },
  }), gate);
}

async function checkAccess(request: Request, env: Env, url: URL): Promise<Response | null> {
  const team = env.ACCESS_TEAM_DOMAIN ?? "";
  const audience = env.ACCESS_AUD ?? "";
  const email = normalizeEmail(env.MANAGEMENT_ADMIN_EMAIL ?? "");
  // Issuer and key URL come exclusively from deployment configuration, never from the JWT.
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.cloudflareaccess\.com$/.test(team)
    || !/^[a-f0-9]{64}$/.test(audience) || !email || !env.MANAGEMENT_HOST) {
    return deny(request, 503, "misconfigured");
  }
  if (url.protocol !== "https:" || url.host !== env.MANAGEMENT_HOST) return deny(request, 404, "wrong-host");
  const assertion = request.headers.get("Cf-Access-Jwt-Assertion");
  if (!assertion || assertion.length > 16384) return deny(request, 403, "required");
  try {
    const issuer = `https://${team}`;
    let keys = keySets.get(issuer);
    if (!keys) {
      keys = createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`), {
        timeoutDuration: 5000, cooldownDuration: 30000, cacheMaxAge: 600000,
      });
      keySets.set(issuer, keys);
    }
    const { payload } = await jwtVerify(assertion, keys, {
      algorithms: ["RS256"], issuer, audience,
      requiredClaims: ["exp", "iat", "sub", "email"], clockTolerance: 5,
    });
    if (typeof payload.email !== "string" || normalizeEmail(payload.email) !== email
      || typeof payload.sub !== "string" || !payload.sub
      || typeof payload.iat !== "number" || payload.iat > Date.now() / 1000 + 5) {
      return deny(request, 403, "required");
    }
    return null;
  } catch {
    // Invalid tokens and unavailable signing keys never allow assets or API calls through.
    return deny(request, 403, "required");
  }
}

async function proxyApi(request: Request, env: Env, url: URL, gate: string): Promise<Response> {
  const origin = request.headers.get("Origin");
  const mutation = !["GET", "HEAD", "OPTIONS"].includes(request.method);
  if ((origin !== null && origin !== url.origin)
    || (mutation && origin === null && request.headers.get("Sec-Fetch-Site") !== "same-origin")) {
    return deny(request, 403, "origin-denied");
  }
  const headers = new Headers(request.headers);
  headers.set("Origin", url.origin);
  headers.set("X-Forwarded-Host", url.host);
  for (const name of ["Cf-Access-Jwt-Assertion", "Cf-Access-Authenticated-User-Email", "Cf-Access-Client-Id", "Cf-Access-Client-Secret"]) headers.delete(name);
  // Forward the verified assertion only to the identity exchange; the API verifies it
  // independently against its own environment's issuer, audience and administrator.
  if (gate === "access" && request.method === "POST" && url.pathname === "/v1/auth/access") {
    headers.set("Cf-Access-Jwt-Assertion", request.headers.get("Cf-Access-Jwt-Assertion")!);
  }
  headers.set("Cookie", (headers.get("Cookie") ?? "").split(";")
    .filter((part) => !["CF_Authorization", "__Host-dst_manager_gate"].includes(part.trim().split("=")[0])).join(";"));
  // All other API routes continue to require their normal account/session authorization.
  return protectResponse(await env.API.fetch(new Request(request, { headers })), gate);
}

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const gated = env.ACCESS_REQUIRED === "true";
  if (gated) {
    const failure = await checkAccess(request, env, url);
    if (failure) return failure;
  } else if (!["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
    return deny(request, 503, "misconfigured");
  }
  const gate = gated ? "access" : "local";
  if (url.pathname === "/sw.js" && ["GET", "HEAD"].includes(request.method)) {
    return protectResponse(new Response(request.method === "HEAD" ? null : retireConnectedWorker, {
      headers: { "Content-Type": "application/javascript; charset=utf-8", "Service-Worker-Allowed": "/" },
    }), gate);
  }
  if (url.pathname.startsWith("/__gate/")) return deny(request, 404, "retired");
  if (url.pathname.startsWith("/v1/") || url.pathname === "/health") return proxyApi(request, env, url, gate);
  return protectResponse(await env.ASSETS.fetch(request), gate);
}

export default { fetch: handleRequest } satisfies ExportedHandler<Env>;
