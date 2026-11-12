CREATE TABLE IF NOT EXISTS installations (
  id TEXT PRIMARY KEY,
  manufacturer TEXT NOT NULL,
  model TEXT NOT NULL,
  device_type TEXT NOT NULL,
  app_version TEXT NOT NULL,
  first_seen TEXT NOT NULL DEFAULT (datetime('now')),
  last_seen TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS installations_type_last_seen ON installations(device_type, last_seen);
