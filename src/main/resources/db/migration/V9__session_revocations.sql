-- Review of PR #89: the API's own end of a user's sessions, so a password change or a staff reset closes
-- every old session even when Keycloak fails to sign the user out. An API session opened at or before
-- revoked_at is refused (SessionValidityFilter). One row per user, moved forward by each change or reset.
-- Its own table, not a users column: a login saving the users row must never write an old value back.
CREATE TABLE session_revocations (
    user_id    UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    revoked_at TIMESTAMPTZ NOT NULL
);
