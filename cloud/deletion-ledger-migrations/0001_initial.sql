CREATE TABLE deletion_ledger (
  id TEXT PRIMARY KEY,
  scope TEXT NOT NULL CHECK (scope IN ('ACCOUNT', 'SPACE')),
  target_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  UNIQUE(scope, target_id)
);

CREATE INDEX idx_deletion_ledger_created ON deletion_ledger(created_at, id);
CREATE INDEX idx_deletion_ledger_expiry ON deletion_ledger(expires_at);
