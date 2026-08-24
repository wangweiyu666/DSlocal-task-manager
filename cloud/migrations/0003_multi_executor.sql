DROP INDEX IF EXISTS uq_space_active_executor;
DROP INDEX IF EXISTS uq_space_active_invitation;

CREATE UNIQUE INDEX uq_space_active_invitation_email
ON invitations(space_id, email_normalized)
WHERE status = 'ACTIVE';

UPDATE schema_metadata
SET value = '3', updated_at = '2026-08-24T00:00:00Z'
WHERE key = 'cloud_schema_version';
