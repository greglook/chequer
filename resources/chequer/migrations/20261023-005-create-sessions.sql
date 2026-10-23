-- Login sessions, used only in OIDC mode. The id is the random token the
-- session cookie carries.
CREATE TABLE session (
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL);

CREATE INDEX session_user ON session(user_id);

CREATE INDEX session_expiry ON session(expires_at);
