import type { AccountStatus, Bootstrap, ExportPage, SessionTokens, SpaceMember, SyncCommand } from "./types";
import { purgeAllConnectedData } from "./db";

export class CloudApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string, public readonly retryable = false) {
    super(message);
    this.name = "CloudApiError";
  }
}

type RequestOptions = RequestInit & { authenticated?: boolean; mutation?: boolean; retry?: boolean };

export class CloudApi {
  private session: SessionTokens | null = null;
  private refreshInFlight: Promise<SessionTokens> | null = null;
  private initializeInFlight: Promise<SessionTokens> | null = null;
  private accessGate = false;

  setSession(session: SessionTokens | null): void { this.session = session; }
  getSession(): SessionTokens | null { return this.session; }
  requiresAccessLogout(): boolean { return this.accessGate; }

  private async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const headers = new Headers(options.headers);
    if (options.body) headers.set("Content-Type", "application/json");
    if (options.authenticated && this.session) headers.set("Authorization", `Bearer ${this.session.accessToken}`);
    if (options.mutation && this.session) headers.set("X-CSRF-Token", this.session.csrfToken);
    let response: Response;
    try {
      response = await fetch(path, { ...options, headers, credentials: "include" });
    } catch (error) {
      throw new CloudApiError(0, "NETWORK_UNAVAILABLE", "当前无法连接服务器", true);
    }
    if (response.headers.get("X-DStationery-Gate") === "access") this.accessGate = true;
    if (response.redirected || !response.headers.get("Content-Type")?.includes("application/json")) {
      throw new CloudApiError(response.status, "WEB_ACCESS_REQUIRED", "网页访问验证可能已过期，请刷新页面重新验证。");
    }
    const body = await response.json().catch(() => ({})) as Record<string, unknown>;
    if (response.status === 401 && options.authenticated && options.retry !== false) {
      try {
        await this.refresh();
        return this.request<T>(path, { ...options, retry: false });
      } catch {
        this.session = null;
      }
    }
    if (!response.ok) {
      const value = body.error as Record<string, unknown> | undefined;
      const code = String(value?.code ?? "HTTP_ERROR");
      if (code === "ACCOUNT_DELETION_PENDING" || code === "SPACE_DELETION_PENDING") {
        this.session = null;
        await purgeAllConnectedData();
      }
      throw new CloudApiError(response.status, code, String(value?.message ?? "请求失败"), Boolean(value?.retryable));
    }
    return body as T;
  }

  requestChallenge(email: string, purpose: "SIGN_IN" | "SENSITIVE_ACTION" = "SIGN_IN"): Promise<{ challengeId: string }> {
    return this.request("/v1/auth/challenges", { method: "POST", body: JSON.stringify({ email, purpose }) });
  }

  async verify(challengeId: string, email: string, code: string): Promise<SessionTokens> {
    const session = await this.request<SessionTokens>("/v1/auth/verify", { method: "POST", body: JSON.stringify({ challengeId, email, code }) });
    this.session = session;
    return session;
  }

  async initializeSession(): Promise<SessionTokens> {
    if (this.initializeInFlight) return this.initializeInFlight;
    const operation = this.request<SessionTokens>("/v1/auth/access", { method: "POST", retry: false })
      .catch((error: unknown) => {
        // Only the explicitly local endpoint can fall back to the development login.
        if (!this.accessGate && error instanceof CloudApiError && error.code === "ACCESS_UNAVAILABLE") return this.refresh();
        throw error;
      })
      .then((session) => { this.session = session; return session; });
    this.initializeInFlight = operation;
    try { return await operation; }
    finally { if (this.initializeInFlight === operation) this.initializeInFlight = null; }
  }

  async refresh(): Promise<SessionTokens> {
    if (this.refreshInFlight) return this.refreshInFlight;
    const operation = this.request<SessionTokens>("/v1/auth/refresh", { method: "POST", retry: false })
      .catch((error: unknown) => {
        if (this.accessGate && error instanceof CloudApiError && ["UNAUTHENTICATED", "SESSION_EXPIRED", "SESSION_REPLAYED"].includes(error.code)) {
          return this.request<SessionTokens>("/v1/auth/access", { method: "POST", retry: false });
        }
        throw error;
      })
      .then((session) => {
        this.session = session;
        return session;
      });
    this.refreshInFlight = operation;
    try {
      return await operation;
    } finally {
      if (this.refreshInFlight === operation) this.refreshInFlight = null;
    }
  }

  account(): Promise<AccountStatus> { return this.request("/v1/account", { authenticated: true }); }
  acknowledgePrivacy(version: number): Promise<Record<string, unknown>> {
    return this.request("/v1/account/privacy-acknowledgements", { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ version }) });
  }
  verifySensitive(challengeId: string, email: string, code: string): Promise<{ sensitiveVerifiedAt: string }> {
    return this.request("/v1/auth/verify", { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ challengeId, email, code }) });
  }
  requestDeletion(mode: "SCHEDULED" | "IMMEDIATE"): Promise<{ status: string; executeAfter?: string; completedAt?: string }> {
    return this.request("/v1/account/deletion-requests", { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ mode }) });
  }
  cancelDeletion(): Promise<Record<string, unknown>> {
    return this.request("/v1/account/deletion-cancellations", { method: "POST", authenticated: true, mutation: true, body: "{}" });
  }
  exportPage(spaceId: string, cursor?: string): Promise<ExportPage> {
    return this.request(`/v1/spaces/${spaceId}/export${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`, { authenticated: true });
  }
  bootstrap(): Promise<Bootstrap> { return this.request("/v1/bootstrap", { authenticated: true }); }
  createSpace(name: string): Promise<{ space: { id: string; name: string; role: "ADMIN" } }> {
    return this.request("/v1/spaces", { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ name }) });
  }
  snapshot(spaceId: string, cursor?: string): Promise<Record<string, unknown>> { return this.request(`/v1/spaces/${spaceId}/snapshot${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`, { authenticated: true }); }
  changes(spaceId: string, cursor: string): Promise<Record<string, unknown>> { return this.request(`/v1/spaces/${spaceId}/changes?cursor=${encodeURIComponent(cursor)}`, { authenticated: true }); }
  commands(spaceId: string, commands: SyncCommand[]): Promise<{ results: Array<Record<string, unknown>> }> {
    return this.request(`/v1/spaces/${spaceId}/commands`, { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ commands }) });
  }
  invite(spaceId: string, email: string): Promise<Record<string, unknown>> {
    return this.request(`/v1/spaces/${spaceId}/invitations`, { method: "POST", authenticated: true, mutation: true, body: JSON.stringify({ email }) });
  }
  members(spaceId: string): Promise<{ members: SpaceMember[] }> {
    return this.request(`/v1/spaces/${spaceId}/members`, { authenticated: true });
  }
  removeMember(spaceId: string, memberId: string): Promise<Record<string, unknown>> {
    return this.request(`/v1/spaces/${spaceId}/members/${memberId}`, { method: "DELETE", authenticated: true, mutation: true });
  }
  notifications(spaceId: string): Promise<{ notifications: Array<Record<string, unknown>>; unreadCount: number }> {
    return this.request(`/v1/spaces/${spaceId}/notifications`, { authenticated: true });
  }
  audit(spaceId: string): Promise<{ events: Array<Record<string, unknown>> }> {
    return this.request(`/v1/spaces/${spaceId}/audit`, { authenticated: true });
  }
  logout(): Promise<Record<string, unknown>> { return this.request("/v1/auth/logout", { method: "POST", authenticated: true, mutation: true }); }
}

export const cloudApi = new CloudApi();
