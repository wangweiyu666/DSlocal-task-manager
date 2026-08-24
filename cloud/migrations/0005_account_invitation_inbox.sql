CREATE INDEX idx_invitations_account_inbox
ON invitations(email_normalized, status, expires_at, created_at);

UPDATE schema_metadata
SET value = '5', updated_at = '2026-08-24T00:00:00Z'
WHERE key = 'cloud_schema_version';
