-- These rows exist only inside a command batch. The constraint aborts every
-- write if its preceding reads were invalidated by another space mutation.
CREATE TABLE sync_write_guards (
  space_id TEXT PRIMARY KEY REFERENCES spaces(id),
  expected_sequence INTEGER NOT NULL,
  actual_sequence INTEGER NOT NULL,
  CHECK (expected_sequence = actual_sequence)
);

UPDATE schema_metadata
SET value = '7', updated_at = '2026-09-05T00:00:00Z'
WHERE key = 'cloud_schema_version';
