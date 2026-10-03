-- Per-user fuel-grade filter for /nearby. CSV of grades (e.g. "95,98"); NULL = no filter (show all).
ALTER TABLE bot_users ADD COLUMN fuel_filter VARCHAR(64);
