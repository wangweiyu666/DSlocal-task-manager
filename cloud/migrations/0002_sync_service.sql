PRAGMA foreign_keys = ON;

ALTER TABLE spaces ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'Asia/Hong_Kong';
ALTER TABLE spaces ADD COLUMN time_zone_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE spaces ADD COLUMN time_zone_changed_at TEXT;

CREATE TABLE task_groups (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  current_revision INTEGER NOT NULL CHECK (current_revision >= 1),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(space_id, id)
);
CREATE INDEX idx_task_groups_space_status ON task_groups(space_id, status, updated_at);

CREATE TABLE task_group_revisions (
  group_id TEXT NOT NULL REFERENCES task_groups(id),
  revision INTEGER NOT NULL,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  content_json TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  created_by_membership_id TEXT NOT NULL REFERENCES memberships(id),
  created_at TEXT NOT NULL,
  PRIMARY KEY(group_id, revision)
);
CREATE INDEX idx_task_group_revisions_space ON task_group_revisions(space_id, created_at);

CREATE UNIQUE INDEX uq_assignment_task_executor ON assignments(space_id, task_id, executor_membership_id);

CREATE TABLE task_occurrences (
  occurrence_key TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES spaces(id),
  assignment_id TEXT NOT NULL REFERENCES assignments(id),
  task_id TEXT NOT NULL REFERENCES tasks(id),
  task_revision INTEGER NOT NULL,
  time_zone_version INTEGER NOT NULL,
  local_date TEXT NOT NULL,
  scheduled_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CANCELLED', 'ARCHIVED')),
  generated_by TEXT NOT NULL CHECK (generated_by IN ('SERVER', 'CLIENT')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(space_id, assignment_id, occurrence_key)
);
CREATE INDEX idx_occurrences_assignment_date ON task_occurrences(space_id, assignment_id, local_date);
CREATE INDEX idx_occurrences_space_schedule ON task_occurrences(space_id, scheduled_at, status);

ALTER TABLE execution_events ADD COLUMN occurrence_key TEXT REFERENCES task_occurrences(occurrence_key);
ALTER TABLE execution_events ADD COLUMN payload_version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_execution_occurrence ON execution_events(space_id, occurrence_key, received_at);

ALTER TABLE result_selections ADD COLUMN occurrence_key TEXT REFERENCES task_occurrences(occurrence_key);
CREATE INDEX idx_result_selection_occurrence ON result_selections(space_id, occurrence_key, created_at DESC);

ALTER TABLE notifications ADD COLUMN group_key TEXT;
ALTER TABLE notifications ADD COLUMN payload_version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_notifications_group ON notifications(space_id, recipient_membership_id, group_key, created_at);

ALTER TABLE space_changes ADD COLUMN payload_version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE space_entities (
  space_id TEXT NOT NULL REFERENCES spaces(id),
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  entity_version INTEGER NOT NULL,
  payload_version INTEGER NOT NULL DEFAULT 1,
  payload_json TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  change_sequence INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(space_id, entity_type, entity_id)
);
CREATE INDEX idx_space_entities_page ON space_entities(space_id, entity_type, entity_id);

INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence)
SELECT id,'space',id,time_zone_version,1,
       json_object('id',id,'name',name,'status',status,'timeZone',time_zone,'timeZoneVersion',time_zone_version),
       created_at,current_sequence
FROM spaces;

INSERT INTO space_entities(space_id,entity_type,entity_id,entity_version,payload_version,payload_json,updated_at,change_sequence)
SELECT space_id,'membership',id,1,1,
       json_object('id',id,'role',role,'status',status,'joinedAt',joined_at),
       COALESCE(removed_at,joined_at),(SELECT current_sequence FROM spaces WHERE spaces.id=memberships.space_id)
FROM memberships;

CREATE TABLE sync_security_events (
  id TEXT PRIMARY KEY,
  space_id TEXT REFERENCES spaces(id),
  session_id TEXT REFERENCES device_sessions(id),
  event_type TEXT NOT NULL,
  safe_metadata_json TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);
CREATE INDEX idx_sync_security_space_time ON sync_security_events(space_id, occurred_at);

UPDATE schema_metadata
SET value = '2', updated_at = '2026-08-17T00:00:00Z'
WHERE key = 'cloud_schema_version';
