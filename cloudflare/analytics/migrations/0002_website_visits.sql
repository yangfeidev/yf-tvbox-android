CREATE TABLE IF NOT EXISTS website_visits (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  visited_at TEXT NOT NULL DEFAULT (datetime('now')),
  country TEXT NOT NULL DEFAULT '',
  region TEXT NOT NULL DEFAULT '',
  city TEXT NOT NULL DEFAULT '',
  device TEXT NOT NULL,
  browser TEXT NOT NULL,
  os TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT '',
  referrer TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS website_visits_visited_at ON website_visits(visited_at);
