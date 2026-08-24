PRAGMA foreign_keys = ON;

CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  email_normalized TEXT NOT NULL UNIQUE,
  email_delivery TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETION_PENDING', 'DELETED')),
  created_at TEXT NOT NULL,
  verified_at TEXT NOT NULL,
  deletion_due_at TEXT
);

CREATE TABLE email_challenges (
  id TEXT PRIMARY KEY,
  email_normalized TEXT NOT NULL,
  email_delivery TEXT NOT NULL,
  purpose TEXT NOT NULL CHECK (purpose IN ('SIGN_IN', 'DELETE_ACCOUNT')),
  code_digest TEXT NOT NULL,
  attempts_remaining INTEGER NOT NULL CHECK (attempts_remaining BETWEEN 0 AND 5),
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  request_ip_digest TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_email_challenges_email_created ON email_challenges(email_normalized, created_at DESC);
CREATE INDEX idx_email_challenges_ip_created ON email_challenges(request_ip_digest, created_at DESC);

CREATE TABLE device_sessions (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  access_digest TEXT NOT NULL UNIQUE,
  refresh_digest TEXT NOT NULL UNIQUE,
  csrf_digest TEXT NOT NULL,
  previous_refresh_digest TEXT UNIQUE,
  previous_refresh_valid_until TEXT,
  replay_bundle_ciphertext TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  access_expires_at TEXT NOT NULL,
  idle_expires_at TEXT NOT NULL,
  absolute_expires_at TEXT NOT NULL,
  revoked_at TEXT,
  revoke_reason TEXT
);
CREATE INDEX idx_device_sessions_account ON device_sessions(account_id, revoked_at);

CREATE TABLE spaces (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETION_PENDING', 'DELETED')),
  created_at TEXT NOT NULL,
  deletion_due_at TEXT,
  current_sequence INTEGER NOT NULL DEFAULT 0 CHECK (current_sequence >= 0)
);

CREATE TABLE memberships (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  account_id TEXT REFERENCES accounts(id),
  role TEXT NOT NULL CHECK (role IN ('ADMIN', 'EXECUTOR')),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED', 'DELETED')),
  joined_at TEXT NOT NULL,
  removed_at TEXT,
  tombstone_id TEXT,
  UNIQUE(space_id, account_id)
);
CREATE UNIQUE INDEX uq_space_active_admin ON memberships(space_id) WHERE role = 'ADMIN' AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_space_active_executor ON memberships(space_id) WHERE role = 'EXECUTOR' AND status = 'ACTIVE';
CREATE UNIQUE INDEX uq_account_active_admin ON memberships(account_id) WHERE role = 'ADMIN' AND status = 'ACTIVE';
CREATE INDEX idx_memberships_account ON memberships(account_id, status);

CREATE TABLE invitations (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  email_normalized TEXT NOT NULL,
  token_digest TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
  created_by_membership_id TEXT NOT NULL REFERENCES memberships(id),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  accepted_at TEXT
);
CREATE UNIQUE INDEX uq_space_active_invitation ON invitations(space_id) WHERE status = 'ACTIVE';

CREATE TABLE tasks (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  current_revision INTEGER NOT NULL CHECK (current_revision >= 1),
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED', 'ARCHIVED')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(space_id, id)
);
CREATE INDEX idx_tasks_space_status ON tasks(space_id, status, updated_at);

CREATE TABLE task_revisions (
  task_id TEXT NOT NULL REFERENCES tasks(id),
  revision INTEGER NOT NULL,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  content_json TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  created_by_membership_id TEXT NOT NULL REFERENCES memberships(id),
  created_at TEXT NOT NULL,
  PRIMARY KEY(task_id, revision)
);
CREATE INDEX idx_task_revisions_space ON task_revisions(space_id, created_at);

CREATE TABLE assignments (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  task_id TEXT NOT NULL REFERENCES tasks(id),
  executor_membership_id TEXT NOT NULL REFERENCES memberships(id),
  assigned_revision INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED', 'ARCHIVED')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_assignments_executor ON assignments(space_id, executor_membership_id, status);

CREATE TABLE execution_events (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  assignment_id TEXT NOT NULL REFERENCES assignments(id),
  executor_membership_id TEXT REFERENCES memberships(id),
  task_revision INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  payload_json TEXT,
  review_reason TEXT CHECK (review_reason IN ('STALE_REVISION', 'CANCELLED_AFTER_SEEN') OR review_reason IS NULL),
  duplicate_of TEXT REFERENCES execution_events(id),
  occurred_at TEXT NOT NULL,
  received_at TEXT NOT NULL,
  tombstone_id TEXT
);
CREATE INDEX idx_execution_assignment ON execution_events(space_id, assignment_id, received_at);

CREATE TABLE result_selections (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  assignment_id TEXT NOT NULL REFERENCES assignments(id),
  execution_event_id TEXT REFERENCES execution_events(id),
  selected_by_membership_id TEXT NOT NULL REFERENCES memberships(id),
  reason TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_result_selection_current ON result_selections(space_id, assignment_id, created_at DESC);

CREATE TABLE notifications (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  recipient_membership_id TEXT NOT NULL REFERENCES memberships(id),
  type TEXT NOT NULL,
  entity_id TEXT,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_notifications_recipient ON notifications(space_id, recipient_membership_id, created_at);

CREATE TABLE notification_reads (
  notification_id TEXT NOT NULL REFERENCES notifications(id),
  membership_id TEXT NOT NULL REFERENCES memberships(id),
  read_at TEXT NOT NULL,
  PRIMARY KEY(notification_id, membership_id)
);

CREATE TABLE command_receipts (
  command_id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  session_id TEXT NOT NULL REFERENCES device_sessions(id),
  payload_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'CONFLICT', 'REJECTED')),
  response_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT
);
CREATE INDEX idx_command_receipts_space_created ON command_receipts(space_id, created_at);

CREATE TABLE space_changes (
  space_id TEXT NOT NULL REFERENCES spaces(id),
  sequence INTEGER NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE', 'TOMBSTONE')),
  entity_version INTEGER,
  payload_json TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  PRIMARY KEY(space_id, sequence)
);
CREATE INDEX idx_space_changes_expiry ON space_changes(expires_at);

CREATE TABLE audit_events (
  id TEXT PRIMARY KEY,
  space_id TEXT REFERENCES spaces(id),
  actor_account_id TEXT REFERENCES accounts(id),
  actor_membership_id TEXT REFERENCES memberships(id),
  event_type TEXT NOT NULL,
  entity_type TEXT,
  entity_id TEXT,
  safe_metadata_json TEXT NOT NULL,
  request_id TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);
CREATE INDEX idx_audit_space_time ON audit_events(space_id, occurred_at);

CREATE TABLE deletion_requests (
  id TEXT PRIMARY KEY,
  account_id TEXT REFERENCES accounts(id),
  space_id TEXT REFERENCES spaces(id),
  scope TEXT NOT NULL CHECK (scope IN ('ACCOUNT', 'SPACE')),
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'CANCELLED', 'COMPLETED')),
  requested_at TEXT NOT NULL,
  execute_after TEXT NOT NULL,
  completed_at TEXT
);

CREATE TABLE schema_metadata (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
INSERT INTO schema_metadata(key, value, updated_at) VALUES ('cloud_schema_version', '1', '2026-08-17T00:00:00Z');

CREATE TABLE dev_mailbox (
  id TEXT PRIMARY KEY,
  recipient TEXT NOT NULL,
  kind TEXT NOT NULL,
  secret TEXT NOT NULL,
  created_at TEXT NOT NULL
);
