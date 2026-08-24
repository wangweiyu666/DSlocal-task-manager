ALTER TABLE tasks
ADD COLUMN assignment_mode TEXT NOT NULL DEFAULT 'ALL'
CHECK (assignment_mode IN ('ALL', 'SELECTED'));

ALTER TABLE task_revisions
ADD COLUMN assignment_mode TEXT NOT NULL DEFAULT 'ALL'
CHECK (assignment_mode IN ('ALL', 'SELECTED'));

ALTER TABLE task_revisions
ADD COLUMN executor_membership_ids_json TEXT NOT NULL DEFAULT '[]';

UPDATE schema_metadata
SET value = '4', updated_at = '2026-08-24T00:00:00Z'
WHERE key = 'cloud_schema_version';
