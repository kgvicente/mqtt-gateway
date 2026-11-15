ALTER TABLE routes ADD COLUMN telegram_json TEXT;

CREATE TABLE IF NOT EXISTS notification_outbox (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    backend_id TEXT,
    incident_id TEXT,
    type TEXT NOT NULL,
    chat_id TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    next_attempt_at TEXT NOT NULL,
    last_attempt_at TEXT,
    sent_at TEXT,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next ON notification_outbox(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_outbox_chat_sequence ON notification_outbox(chat_id, sequence);
CREATE INDEX IF NOT EXISTS idx_outbox_created_at ON notification_outbox(created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_incident_type_chat ON notification_outbox(incident_id, type, chat_id) WHERE incident_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS telegram_chat_activity (
    chat_id TEXT PRIMARY KEY,
    last_attempt_at TEXT NOT NULL
);
