-- Additive, safe for older clients. One opted-in device token per account session.
CREATE TABLE IF NOT EXISTS notification_devices (
    token_hash BYTEA PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    session_hash BYTEA NOT NULL UNIQUE REFERENCES sessions(token_hash) ON DELETE CASCADE,
    encrypted_token BYTEA NOT NULL CHECK (octet_length(encrypted_token) BETWEEN 128 AND 8192),
    binding UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS notification_devices_updated_idx ON notification_devices(updated_at);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'roadconquest_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON notification_devices TO roadconquest_runtime;
    END IF;
END $$;
