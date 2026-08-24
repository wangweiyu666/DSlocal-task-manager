PRAGMA foreign_keys = OFF;

ALTER TABLE accounts ADD COLUMN privacy_notice_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN privacy_acknowledged_at TEXT;

ALTER TABLE device_sessions ADD COLUMN sensitive_verified_at TEXT;

CREATE TABLE email_challenges_v6 (
  id TEXT PRIMARY KEY,
  email_normalized TEXT NOT NULL,
  email_delivery TEXT NOT NULL,
  purpose TEXT NOT NULL CHECK (purpose IN ('SIGN_IN', 'SENSITIVE_ACTION', 'DELETE_ACCOUNT')),
  code_digest TEXT NOT NULL,
  attempts_remaining INTEGER NOT NULL CHECK (attempts_remaining BETWEEN 0 AND 5),
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  request_ip_digest TEXT NOT NULL,
  created_at TEXT NOT NULL
);

INSERT INTO email_challenges_v6(
  id,email_normalized,email_delivery,purpose,code_digest,attempts_remaining,
  expires_at,consumed_at,request_ip_digest,created_at
)
SELECT id,email_normalized,email_delivery,purpose,code_digest,attempts_remaining,
       expires_at,consumed_at,request_ip_digest,created_at
FROM email_challenges;

DROP TABLE email_challenges;
ALTER TABLE email_challenges_v6 RENAME TO email_challenges;
CREATE INDEX idx_email_challenges_email_created ON email_challenges(email_normalized, created_at DESC);
CREATE INDEX idx_email_challenges_ip_created ON email_challenges(request_ip_digest, created_at DESC);

CREATE TABLE service_usage_daily (
  usage_date TEXT NOT NULL,
  resource TEXT NOT NULL CHECK (resource IN ('EMAIL', 'AUTO_SYNC_READ', 'API_WRITE')),
  used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
  soft_limit INTEGER NOT NULL CHECK (soft_limit > 0),
  updated_at TEXT NOT NULL,
  PRIMARY KEY(usage_date, resource)
);

INSERT INTO schema_metadata(key, value, updated_at)
VALUES ('deletion_ledger_checkpoint', '', '2026-08-24T00:00:00Z')
ON CONFLICT(key) DO NOTHING;

UPDATE schema_metadata
SET value = '6', updated_at = '2026-08-24T00:00:00Z'
WHERE key = 'cloud_schema_version';

PRAGMA foreign_keys = ON;
