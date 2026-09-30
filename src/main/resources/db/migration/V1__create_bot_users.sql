CREATE TABLE bot_users (
    user_id           BIGINT       PRIMARY KEY,
    chat_id           BIGINT       NOT NULL,
    username          VARCHAR(255),
    first_name        VARCHAR(255),
    role              VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    status_changed_at TIMESTAMPTZ  NOT NULL
);

-- Enforce at most one admin at the database level (bootstrap race safety net).
CREATE UNIQUE INDEX uq_bot_users_single_admin ON bot_users (role) WHERE role = 'ADMIN';

CREATE INDEX idx_bot_users_status ON bot_users (status);
