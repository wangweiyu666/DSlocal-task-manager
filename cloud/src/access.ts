import { createRemoteJWKSet, jwtVerify } from "jose";
import { ApiError } from "./http";
import type { Env } from "./types";

const keySets = new Map<string, ReturnType<typeof createRemoteJWKSet>>();
const normalized = (value: string) => value.trim().normalize("NFC").toLowerCase();

// Never trust an email header or token-provided issuer/key URL as an identity.
export async function accessIdentity(env: Env, request: Request): Promise<string> {
  if (env.ENVIRONMENT === "local") throw new ApiError(404, "ACCESS_UNAVAILABLE", "本地环境使用邮箱登录");
  const team = env.ACCESS_TEAM_DOMAIN ?? "";
  const audience = env.ACCESS_AUD ?? "";
  const email = normalized(env.ADMIN_EMAIL ?? "");
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.cloudflareaccess\.com$/.test(team)
    || !/^[a-f0-9]{64}$/.test(audience) || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
    || !env.ALLOWED_ORIGIN?.startsWith("https://")) {
    throw new ApiError(503, "ACCESS_MISCONFIGURED", "管理员登录暂不可用");
  }
  if (request.headers.get("Origin") !== env.ALLOWED_ORIGIN) throw new ApiError(403, "FORBIDDEN", "登录来源无效");
  const assertion = request.headers.get("Cf-Access-Jwt-Assertion");
  if (!assertion || assertion.length > 16384) throw new ApiError(403, "WEB_ACCESS_REQUIRED", "请重新完成网页访问验证");
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
    if (typeof payload.email !== "string" || normalized(payload.email) !== email
      || typeof payload.sub !== "string" || !payload.sub
      || typeof payload.iat !== "number" || payload.iat > Date.now() / 1000 + 5) throw new Error("invalid identity");
    return email;
  } catch {
    throw new ApiError(403, "WEB_ACCESS_REQUIRED", "请重新完成网页访问验证");
  }
}
