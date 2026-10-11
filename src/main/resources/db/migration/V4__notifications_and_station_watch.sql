-- Per-user notification settings for favorite-station alerts. Quiet hours are whole hours in MSK
-- (0..23, the interval may wrap past midnight); during them alerts are delivered silently.
ALTER TABLE bot_users ADD COLUMN notify_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bot_users ADD COLUMN quiet_enabled  BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bot_users ADD COLUMN quiet_from     INTEGER NOT NULL DEFAULT 23;
ALTER TABLE bot_users ADD COLUMN quiet_to       INTEGER NOT NULL DEFAULT 7;

-- Last seen state of each polled favorite station (shared across users), so a restart does not
-- re-send alerts. "fuels" is the normalized grade set, e.g. "92,95,ДТ".
CREATE TABLE station_watch (
    osm_id       VARCHAR(64)  PRIMARY KEY,
    last_updated TIMESTAMPTZ,
    status       VARCHAR(16),
    fuels        VARCHAR(128),
    checked_at   TIMESTAMPTZ  NOT NULL
);
