import type { Bootstrap, SessionTokens, SpaceMember, SyncCommand } from "./types";

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

  setSession(session: SessionTokens | null): void { this.session = session; }
  getSession(): SessionTokens | null { return this.session; }

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
      throw new CloudApiError(response.status, String(value?.code ?? "HTTP_ERROR"), String(value?.message ?? "请求失败"), Boolean(value?.retryable));
    }
    return body as T;
  }

  requestChallenge(email: string): Promise<{ challengeId: string }> {
    return this.request("/v1/auth/challenges", { method: "POST", body: JSON.stringify({ email }) });
  }

  async verify(challengeId: string, email: string, code: string): Promise<SessionTokens> {
    const session = await this.request<SessionTokens>("/v1/auth/verify", { method: "POST", body: JSON.stringify({ challengeId, email, code }) });
    this.session = session;
    return session;
  }

  async refresh(): Promise<SessionTokens> {
    if (this.refreshInFlight) return this.refreshInFlight;
    const operation = this.request<SessionTokens>("/v1/auth/refresh", { method: "POST", retry: false })
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

  bootstrap(): Promise<Bootstrap> { return this.request("/v1/bootstrap", { authenticated: true }); }
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
