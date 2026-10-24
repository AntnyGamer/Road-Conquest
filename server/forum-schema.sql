-- Public community data is separate from private driving history and competitive eligibility.
CREATE TABLE IF NOT EXISTS forum_profiles (
  user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  violations INTEGER NOT NULL DEFAULT 0 CHECK (violations BETWEEN 0 AND 3),
  muted_until TIMESTAMPTZ,
  banned BOOLEAN NOT NULL DEFAULT FALSE,
  rules_version INTEGER NOT NULL DEFAULT 0,
  last_post_at TIMESTAMPTZ,
  plus_active BOOLEAN NOT NULL DEFAULT FALSE
);
ALTER TABLE forum_profiles ADD COLUMN IF NOT EXISTS last_post_at TIMESTAMPTZ;
CREATE TABLE IF NOT EXISTS forum_posts (
  id UUID PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  body VARCHAR(1200) NOT NULL DEFAULT '',
  gif_url VARCHAR(1000),
  gif_hash VARCHAR(64),
  state VARCHAR(12) NOT NULL CHECK (state IN ('visible','removed','deleted')),
  moderation JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id,request_id),
  CHECK ((gif_url IS NULL) = (gif_hash IS NULL))
);
CREATE INDEX IF NOT EXISTS forum_posts_feed_idx ON forum_posts(created_at DESC,id DESC) WHERE state='visible';
CREATE INDEX IF NOT EXISTS forum_posts_user_idx ON forum_posts(user_id);
CREATE TABLE IF NOT EXISTS forum_reactions (
  post_id UUID NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reaction VARCHAR(12) NOT NULL CHECK (reaction IN ('like','love','celebrate')),
  PRIMARY KEY(post_id,user_id)
);
CREATE INDEX IF NOT EXISTS forum_reactions_user_idx ON forum_reactions(user_id);
CREATE TABLE IF NOT EXISTS forum_blocks (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY(user_id,blocked_id), CHECK(user_id<>blocked_id)
);
CREATE TABLE IF NOT EXISTS forum_reports (
  post_id UUID NOT NULL REFERENCES forum_posts(id) ON DELETE CASCADE,
  reporter_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason VARCHAR(20) NOT NULL CHECK (reason IN ('harassment','hate','sexual','violence','spam','other')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY(post_id,reporter_id)
);
CREATE INDEX IF NOT EXISTS forum_reports_pending_idx ON forum_reports(created_at) WHERE NOT reviewed;
CREATE INDEX IF NOT EXISTS forum_reports_reporter_idx ON forum_reports(reporter_id);
CREATE TABLE IF NOT EXISTS forum_purchases (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  encrypted_token BYTEA NOT NULL,
  active BOOLEAN NOT NULL,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS forum_purchases_user_idx ON forum_purchases(user_id);
CREATE TABLE IF NOT EXISTS forum_billing_events (
  id VARCHAR(128) PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS forum_billing_events_age_idx ON forum_billing_events(created_at);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='roadconquest_runtime') THEN
    GRANT SELECT,INSERT,UPDATE,DELETE ON forum_profiles,forum_posts,forum_reactions,forum_blocks,
      forum_reports,forum_purchases,forum_billing_events TO roadconquest_runtime;
  END IF;
END $$;
