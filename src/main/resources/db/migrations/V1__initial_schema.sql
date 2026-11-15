CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    checksum TEXT NOT NULL,
    applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS routes (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    auth_url TEXT NOT NULL,
    acl_url TEXT NOT NULL,
    health_url TEXT NOT NULL,
    api_key_env TEXT,
    auth_prefixes TEXT NOT NULL,
    acl_prefixes TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS incidents (
    id TEXT PRIMARY KEY,
    backend_id TEXT NOT NULL,
    backend_name TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    duration_ms INTEGER,
    reason TEXT,
    end_reason TEXT
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS checkpoints (
    checkpoint_date TEXT PRIMARY KEY,
    state_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
