CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(30) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (char_length(username) BETWEEN 4 AND 30),
    CHECK (role IN ('PATIENT', 'ADMINISTRATOR', 'RECEPTIONIST', 'OPHTHALMOLOGIST')),
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISABLED')),
    CHECK (role = 'PATIENT' OR (display_name IS NOT NULL AND btrim(display_name) <> ''))
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions (user_id);
