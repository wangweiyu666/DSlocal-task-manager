export interface Env {
  DB: D1Database;
  ENVIRONMENT: "local" | "staging" | "production";
  ALLOWED_ORIGIN: string;
  AUTH_PEPPER: string;
  ADMIN_EMAIL: string;
  RESEND_API_KEY: string;
  RESEND_FROM: string;
}

export interface SessionPrincipal {
  accountId: string;
  sessionId: string;
  email: string;
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
