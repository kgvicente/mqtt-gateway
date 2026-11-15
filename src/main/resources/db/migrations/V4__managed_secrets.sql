-- V4__managed_secrets.sql
-- Tabla dedicada para secretos cifrados con AES-256-GCM (write-only desde panel)

CREATE TABLE IF NOT EXISTS managed_secrets (
    owner TEXT NOT NULL PRIMARY KEY,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version = 1),
    nonce BLOB NOT NULL CHECK (length(nonce) = 12),
    ciphertext BLOB NOT NULL CHECK (length(ciphertext) >= 16),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
