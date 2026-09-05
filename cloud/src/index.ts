import { logout, refreshSession, requestChallenge, verifyChallenge } from "./auth";
import { accountStatus, acknowledgePrivacy, cancelDeletion, enforceDeletionLedger, exportData, requestDeletion, runMaintenance } from "./account";
import { ApiError, assertOrigin, errorResponse, json, securityHeaders } from "./http";
import { acceptInvitationById, createInvitation, createSpace, listInvitations, listMembers, removeMember } from "./spaces";
import { auditTimeline, bootstrap, changes, snapshot, submitCommands, unreadNotifications } from "./sync";
import type { Env } from "./types";
import { guardRequest } from "./request-guard";

const exactRouteLabels = new Map<string, string>([
  ["/health", "/health"],
  ["/__dev/mailbox", "/__dev/mailbox"],
  ["/v1/auth/challenges", "/v1/auth/challenges"],
  ["/v1/auth/verify", "/v1/auth/verify"],
  ["/v1/auth/refresh", "/v1/auth/refresh"],
  ["/v1/auth/logout", "/v1/auth/logout"],
  ["/v1/account", "/v1/account"],
  ["/v1/account/privacy-acknowledgements", "/v1/account/privacy-acknowledgements"],
  ["/v1/account/deletion-requests", "/v1/account/deletion-requests"],
  ["/v1/account/deletion-cancellations", "/v1/account/deletion-cancellations"],
  ["/v1/bootstrap", "/v1/bootstrap"],
  ["/v1/invitations", "/v1/invitations"],
  ["/v1/spaces", "/v1/spaces"],
]);

function routeLabel(pathname: string): string {
  const exact = exactRouteLabels.get(pathname);
  if (exact) return exact;
  if (/^\/v1\/invitations\/[^/]+\/accept$/.test(pathname)) return "/v1/invitations/:id/accept";
  if (/^\/v1\/spaces\/[^/]+\/(invitations|snapshot|changes|commands|notifications|audit|export|members)$/.test(pathname)) {
    return pathname.replace(/^\/v1\/spaces\/[^/]+\//, "/v1/spaces/:id/");
  }
  if (/^\/v1\/spaces\/[^/]+\/members\/[^/]+$/.test(pathname)) return "/v1/spaces/:id/members/:id";
  return "unmatched";
}

async function route(request: Request, env: Env): Promise<Response> {
  assertOrigin(env, request);
  const url = new URL(request.url);
  if (request.method === "OPTIONS") {
    const headers = securityHeaders(env, request);
    headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-CSRF-Token");
    headers.set("Access-Control-Max-Age", "600");
    return new Response(null, { status: 204, headers });
  }
  if (request.method === "GET" && url.pathname === "/health") {
    const schema = await env.DB.prepare("SELECT value FROM schema_metadata WHERE key='cloud_schema_version'").first<{ value: string }>();
    return json(env, request, { status: "ok", environment: env.ENVIRONMENT, schemaVersion: schema?.value ?? "missing" });
  }
  if (env.ENVIRONMENT === "local" && request.method === "GET" && url.pathname === "/__dev/mailbox") {
    const recipient = url.searchParams.get("recipient");
    const rows = await env.DB.prepare("SELECT id,recipient,kind,secret,created_at FROM dev_mailbox WHERE recipient=? ORDER BY created_at DESC").bind(recipient ?? "").all();
    return json(env, request, { messages: rows.results });
  }
  if (request.method === "POST" && url.pathname === "/v1/auth/challenges") return requestChallenge(env, request);
  if (request.method === "POST" && url.pathname === "/v1/auth/verify") return verifyChallenge(env, request);
  if (request.method === "POST" && url.pathname === "/v1/auth/refresh") return refreshSession(env, request);
  if (request.method === "POST" && url.pathname === "/v1/auth/logout") return logout(env, request);
  if (request.method === "GET" && url.pathname === "/v1/account") return accountStatus(env, request);
  if (request.method === "POST" && url.pathname === "/v1/account/privacy-acknowledgements") return acknowledgePrivacy(env, request);
  if (request.method === "POST" && url.pathname === "/v1/account/deletion-requests") return requestDeletion(env, request);
  if (request.method === "POST" && url.pathname === "/v1/account/deletion-cancellations") return cancelDeletion(env, request);
  if (request.method === "GET" && url.pathname === "/v1/bootstrap") return bootstrap(env, request);
  if (request.method === "GET" && url.pathname === "/v1/invitations") return listInvitations(env, request);
  if (request.method === "POST" && url.pathname === "/v1/spaces") return createSpace(env, request);
  const invitationAccept = url.pathname.match(/^\/v1\/invitations\/([^/]+)\/accept$/);
  if (request.method === "POST" && invitationAccept) return acceptInvitationById(env, request, invitationAccept[1]);
  const invitation = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/invitations$/);
  if (request.method === "POST" && invitation) return createInvitation(env, request, invitation[1]);
  const snapshotPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/snapshot$/);
  if (request.method === "GET" && snapshotPath) return snapshot(env, request, snapshotPath[1]);
  const changesPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/changes$/);
  if (request.method === "GET" && changesPath) return changes(env, request, changesPath[1]);
  const commandsPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/commands$/);
  if (request.method === "POST" && commandsPath) return submitCommands(env, request, commandsPath[1]);
  const notificationsPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/notifications$/);
  if (request.method === "GET" && notificationsPath) return unreadNotifications(env, request, notificationsPath[1]);
  const auditPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/audit$/);
  if (request.method === "GET" && auditPath) return auditTimeline(env, request, auditPath[1]);
  const exportPath = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/export$/);
  if (request.method === "GET" && exportPath) return exportData(env, request, exportPath[1]);
  const members = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/members$/);
  if (request.method === "GET" && members) return listMembers(env, request, members[1]);
  const member = url.pathname.match(/^\/v1\/spaces\/([^/]+)\/members\/([^/]+)$/);
  if (request.method === "DELETE" && member) return removeMember(env, request, member[1], member[2]);
  throw new ApiError(404, "NOT_FOUND", "接口不存在");
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const started = Date.now();
    const requestRoute = routeLabel(new URL(request.url).pathname);
    try {
      await guardRequest(env, request);
      if (requestRoute === "unmatched") throw new ApiError(404, "NOT_FOUND", "接口不存在");
      if (request.method !== "OPTIONS") await enforceDeletionLedger(env);
      const response = await route(request, env);
      console.log(JSON.stringify({ timestamp: new Date().toISOString(), level: "info", event: "http_request", environment: env.ENVIRONMENT, requestId: response.headers.get("X-Request-Id"), method: request.method, route: requestRoute, status: response.status, durationMs: Date.now() - started }));
      return response;
    } catch (error) {
      const response = errorResponse(env, request, error);
      if (!(error instanceof ApiError)) console.error(JSON.stringify({ timestamp: new Date().toISOString(), level: "error", event: "unexpected_error", environment: env.ENVIRONMENT, requestId: response.headers.get("X-Request-Id"), method: request.method, route: requestRoute, errorName: error instanceof Error ? error.name : "Unknown" }));
      console.log(JSON.stringify({ timestamp: new Date().toISOString(), level: "info", event: "http_request", environment: env.ENVIRONMENT, requestId: response.headers.get("X-Request-Id"), method: request.method, route: requestRoute, status: response.status, errorCode: error instanceof ApiError ? error.code : "INTERNAL_ERROR", retryable: error instanceof ApiError ? error.retryable : true, durationMs: Date.now() - started }));
      return response;
    }
  },
  async scheduled(_controller: ScheduledController, env: Env, context: ExecutionContext): Promise<void> {
    context.waitUntil(runMaintenance(env));
  },
} satisfies ExportedHandler<Env>;
