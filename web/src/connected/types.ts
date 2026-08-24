export type Role = "ADMIN" | "EXECUTOR";

export interface SessionTokens {
  accessToken: string;
  csrfToken: string;
  accessExpiresAt: string;
}

export interface MembershipBootstrap {
  id: string;
  role: Role;
  status: string;
  space: {
    id: string;
    name: string;
    timeZone: string;
    timeZoneVersion: number;
    currentSequence: number;
  };
}

export interface Bootstrap {
  account: { id: string };
  memberships: MembershipBootstrap[];
}

export interface CloudEntity {
  key: string;
  spaceId: string;
  entityType: string;
  entityId: string;
  entityVersion: number;
  payloadVersion: number;
  payload: Record<string, unknown>;
  updatedAt: string;
  pendingCommandId?: string;
}

export interface SyncCommand {
  commandId: string;
  entityId: string;
  baseVersion: number;
  createdAt: string;
  type: string;
  payload: Record<string, unknown>;
}

export interface OutboxRecord {
  commandId: string;
  spaceId: string;
  command: SyncCommand;
  original: Record<string, unknown> | null;
  queuedAt: string;
  attempts: number;
}

export interface ConflictRecord {
  commandId: string;
  spaceId: string;
  entityId: string;
  commandType: string;
  original: Record<string, unknown> | null;
  local: Record<string, unknown>;
  server: Record<string, unknown> | null;
  serverVersion: number;
  createdAt: string;
}

export interface SyncMeta {
  spaceId: string;
  changesCursor: string | null;
  lastSyncedAt: string | null;
}

export interface LocalSetting {
  key: string;
  value: unknown;
}

export interface SpaceMember {
  id: string;
  role: Role;
  status: string;
  joinedAt: string;
  email: string | null;
}
