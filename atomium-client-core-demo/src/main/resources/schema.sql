-- The feed pointer table of the full-monty demo (see DemoJdbcFeedPointerRepository).
CREATE TABLE IF NOT EXISTS demo_feed_pointer (
    feed_id                  VARCHAR(100) PRIMARY KEY,
    last_event_page_link     VARCHAR(500),
    last_event_id            VARCHAR(100),
    next_fetch_page_link VARCHAR(500) NOT NULL,
    next_fetch_page_etag VARCHAR(200)
);
