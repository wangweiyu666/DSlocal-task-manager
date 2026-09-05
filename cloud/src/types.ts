export interface Env {
  API_RATE_LIMITER: RateLimit;
  AUTH_RATE_LIMITER: RateLimit;
  DB: D1Database;
  DELETION_LEDGER: D1Database;
  ENVIRONMENT: "local" | "staging" | "production";
  ALLOWED_ORIGIN: string;
  AUTH_PEPPER: string;
  ADMIN_EMAIL: string;
  ACCESS_TEAM_DOMAIN?: string;
  ACCESS_AUD?: string;
  RESEND_API_KEY: string;
  RESEND_FROM: string;
}

export interface SessionPrincipal {
  accountId: string;
  sessionId: string;
  email: string;
  accountStatus: "ACTIVE" | "DELETION_PENDING" | "DELETED";
  privacyNoticeVersion: number;
  sensitiveVerifiedAt: string | null;
}

export interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    requestId: string;
    retryable: boolean;
    retryAfterSeconds?: number;
  };
}
