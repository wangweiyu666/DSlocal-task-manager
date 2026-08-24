import { ApiError } from "./http";
import type { Env } from "./types";

export type ServiceMode = "NORMAL" | "WARNING" | "PROTECT";
export type EmailPurpose = "SIGN_IN" | "RECOVERY" | "SENSITIVE_ACTION" | "INVITATION";

const date = () => new Date().toISOString().slice(0, 10);

function mode(used: number, limit: number): ServiceMode {
  if (used / limit >= 0.9) return "PROTECT";
  if (used / limit >= 0.7) return "WARNING";
  return "NORMAL";
}

async function usage(env: Env, resource: "EMAIL" | "AUTO_SYNC_READ" | "API_WRITE", softLimit: number): Promise<{ used: number; softLimit: number }> {
  const row = await env.DB.prepare("SELECT used,soft_limit FROM service_usage_daily WHERE usage_date=? AND resource=?").bind(date(), resource).first<{ used: number; soft_limit: number }>();
  return { used: row?.used ?? 0, softLimit: row?.soft_limit ?? softLimit };
}

export async function recordUsage(env: Env, resource: "EMAIL" | "AUTO_SYNC_READ" | "API_WRITE", amount = 1): Promise<void> {
  const softLimit = resource === "EMAIL" ? 100 : resource === "AUTO_SYNC_READ" ? 50_000 : 20_000;
  const now = new Date().toISOString();
  await env.DB.prepare("INSERT INTO service_usage_daily(usage_date,resource,used,soft_limit,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(usage_date,resource) DO UPDATE SET used=used+excluded.used,updated_at=excluded.updated_at")
    .bind(date(), resource, amount, softLimit, now).run();
}

export async function reserveEmail(env: Env, purpose: EmailPurpose): Promise<void> {
  const softLimit = 100;
  const maximumBeforeReservation = purpose === "INVITATION" || purpose === "SIGN_IN" ? Math.ceil(softLimit * 0.9) : softLimit;
  const reserved = await env.DB.prepare("INSERT INTO service_usage_daily(usage_date,resource,used,soft_limit,updated_at) VALUES (?,'EMAIL',1,?,?) ON CONFLICT(usage_date,resource) DO UPDATE SET used=used+1,updated_at=excluded.updated_at WHERE used<?")
    .bind(date(), softLimit, new Date().toISOString(), maximumBeforeReservation).run();
  if ((reserved.meta.changes ?? 0) !== 1) {
    throw new ApiError(503, "EMAIL_CAPACITY_PROTECTED", "邮件额度进入保护状态，请稍后重试", true, 3600);
  }
}

export async function serviceQuotaSnapshot(env: Env): Promise<{ mode: ServiceMode; autoSyncIntervalSeconds: number | null; resources: Record<string, { used: number; softLimit: number; ratio: number }> }> {
  const [email, reads, writes] = await Promise.all([
    usage(env, "EMAIL", 100),
    usage(env, "AUTO_SYNC_READ", 50_000),
    usage(env, "API_WRITE", 20_000),
  ]);
  const resources = { email, autoSyncRead: reads, apiWrite: writes };
  const modes = Object.values(resources).map((item) => mode(item.used, item.softLimit));
  const overall: ServiceMode = modes.includes("PROTECT") ? "PROTECT" : modes.includes("WARNING") ? "WARNING" : "NORMAL";
  return {
    mode: overall,
    autoSyncIntervalSeconds: overall === "PROTECT" ? null : overall === "WARNING" ? 60 : 15,
    resources: Object.fromEntries(Object.entries(resources).map(([key, item]) => [key, { ...item, ratio: item.used / item.softLimit }])),
  };
}
