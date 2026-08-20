CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    type      TEXT    NOT NULL,
    sender    TEXT    NOT NULL,
    recipient TEXT,
    body      TEXT,
    timestamp INTEGER NOT NULL
);

DROP INDEX IF EXISTS idx_users_username;
DROP INDEX IF EXISTS idx_messages_timestamp;
DROP INDEX IF EXISTS idx_messages_recipient;

CREATE INDEX IF NOT EXISTS idx_messages_recent
    ON messages (timestamp DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_messages_recipient_recent
    ON messages (recipient, timestamp DESC, id DESC);
