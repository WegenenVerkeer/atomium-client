-- The table atomium-client-spring-boot expects for the feed pointer persistence.
CREATE TABLE IF NOT EXISTS atomium_feed_pointer_v1 (
    feed_id                  text PRIMARY KEY,
    last_event_page_link     text,
    last_event_id            text,
    next_fetch_page_link text        NOT NULL,
    next_fetch_page_etag text,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
