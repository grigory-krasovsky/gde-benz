-- Keyword alerts from station chats ("бензовоз", "привезли", ...): per-user toggle, and the id of the
-- last processed chat message per station (NULL = not seen yet; the first poll only records a baseline).
ALTER TABLE bot_users ADD COLUMN notify_chat BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE station_watch ADD COLUMN last_chat_id BIGINT;
