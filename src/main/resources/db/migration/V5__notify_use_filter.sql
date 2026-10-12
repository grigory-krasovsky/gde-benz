-- Favorite alerts: when TRUE, only changes of grades from the user's /filters selection are reported.
ALTER TABLE bot_users ADD COLUMN notify_use_filter BOOLEAN NOT NULL DEFAULT FALSE;
