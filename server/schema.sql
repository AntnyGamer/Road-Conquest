CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username_display VARCHAR(24) NOT NULL,
    username_key VARCHAR(24) NOT NULL,
    password_salt BYTEA NOT NULL CHECK (octet_length(password_salt) = 16),
    password_hash BYTEA NOT NULL CHECK (octet_length(password_hash) = 64),
    leaderboard_visible BOOLEAN NOT NULL DEFAULT TRUE,
    leaderboard_eligible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_username_key_unique UNIQUE (username_key),
    CHECK (username_display ~ '^[A-Za-z0-9_]{3,24}$'),
    CHECK (username_key ~ '^[a-z0-9_]{3,24}$'),
    CHECK (username_key = lower(username_display))
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash BYTEA PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS sessions_user_id_idx ON sessions(user_id);
CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS auth_rate_limits (
    bucket VARCHAR(32) NOT NULL,
    key_hash BYTEA NOT NULL CHECK (octet_length(key_hash) = 32),
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (bucket, key_hash)
);

CREATE INDEX IF NOT EXISTS auth_rate_limits_window_idx ON auth_rate_limits(window_started_at);

-- Competition scores belong to a pinned OSM dataset; changing the map starts a new season.
CREATE TABLE IF NOT EXISTS road_catalogs (
    dataset VARCHAR(64) PRIMARY KEY CHECK (dataset ~ '^[a-f0-9]{64}$'),
    data_version TEXT NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS road_edges (
    dataset VARCHAR(64) NOT NULL REFERENCES road_catalogs(dataset),
    node_low BIGINT NOT NULL CHECK (node_low > 0),
    node_high BIGINT NOT NULL CHECK (node_high > node_low),
    way_id BIGINT CHECK (way_id > 0),
    PRIMARY KEY (dataset, node_low, node_high)
);
CREATE TABLE IF NOT EXISTS competition_scores (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dataset VARCHAR(64) NOT NULL REFERENCES road_catalogs(dataset),
    distance_mm BIGINT NOT NULL DEFAULT 0 CHECK (distance_mm >= 0),
    last_fix JSONB,
    PRIMARY KEY (user_id, dataset)
);
-- Legacy totals remain stored, but cannot enter hardened verified-only rankings.
ALTER TABLE competition_scores ADD COLUMN IF NOT EXISTS verified_distance_mm BIGINT NOT NULL DEFAULT 0 CHECK (verified_distance_mm >= 0);
ALTER TABLE competition_scores ADD COLUMN IF NOT EXISTS last_verified_fix JSONB;
CREATE TABLE IF NOT EXISTS competition_roads (
    user_id BIGINT NOT NULL,
    dataset VARCHAR(64) NOT NULL,
    way_id BIGINT NOT NULL CHECK (way_id > 0),
    PRIMARY KEY (user_id, dataset, way_id),
    FOREIGN KEY (user_id, dataset) REFERENCES competition_scores(user_id, dataset) ON DELETE CASCADE
);
ALTER TABLE competition_roads ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS competition_verified_roads_idx ON competition_roads(user_id, dataset) WHERE verified;
CREATE TABLE IF NOT EXISTS competition_runs (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    nonce VARCHAR(43) NOT NULL,
    dataset VARCHAR(64) NOT NULL REFERENCES road_catalogs(dataset),
    sequence INTEGER NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '3 minutes'
);
CREATE TABLE IF NOT EXISTS competition_receipts (
    run_id UUID NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    evidence_hash VARCHAR(43) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, sequence)
);
CREATE INDEX IF NOT EXISTS competition_receipts_age_idx ON competition_receipts(created_at);

-- Written in the same transaction as a verified batch and its idempotency receipt.
CREATE TABLE IF NOT EXISTS competition_weeks (
    user_id BIGINT NOT NULL,
    dataset VARCHAR(64) NOT NULL,
    week_start DATE NOT NULL,
    distance_mm BIGINT NOT NULL DEFAULT 0 CHECK (distance_mm >= 0),
    new_roads BIGINT NOT NULL DEFAULT 0 CHECK (new_roads >= 0),
    PRIMARY KEY (user_id, dataset, week_start),
    FOREIGN KEY (user_id, dataset) REFERENCES competition_scores(user_id, dataset) ON DELETE CASCADE,
    CHECK (EXTRACT(ISODOW FROM week_start) = 1)
);
ALTER TABLE competition_weeks ADD COLUMN IF NOT EXISTS verified_distance_mm BIGINT NOT NULL DEFAULT 0 CHECK (verified_distance_mm >= 0);
ALTER TABLE competition_weeks ADD COLUMN IF NOT EXISTS verified_new_roads BIGINT NOT NULL DEFAULT 0 CHECK (verified_new_roads >= 0);

-- Both users must consent. Username changes leave the relationship intact.
CREATE TABLE IF NOT EXISTS friendships (
    user_low BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    accepted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_low, user_high),
    CHECK (user_low < user_high),
    CHECK (requested_by IN (user_low, user_high))
);
CREATE INDEX IF NOT EXISTS friendships_high_idx ON friendships(user_high);
