-- Test schema for the feed pointer persistence. In a real application the app provides this table
-- (typically with its own Flyway script); the lib does not create it. In the tests this script runs via Flyway
-- (once per container), so no DROP needed.
CREATE TABLE IF NOT EXISTS atomium_feed_pointer_v1 (
    feed_id                  text PRIMARY KEY,
    last_event_page_link     text,
    last_event_id            text,
    next_fetch_page_link text        NOT NULL,
    next_fetch_page_etag text,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
