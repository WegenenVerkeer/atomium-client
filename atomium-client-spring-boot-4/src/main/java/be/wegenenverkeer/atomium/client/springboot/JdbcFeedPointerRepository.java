package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * Default {@link FeedPointerRepository} on {@link JdbcClient}: persists the {@link FeedPointer} per feed in the
 * table {@code atomium_feed_pointer_v1}. The SQL ({@code ON CONFLICT}, {@code now()}) is postgres-specific; another
 * RDBMS supplies its own {@link FeedPointerRepository} bean.
 *
 * <p>The <strong>application</strong> provides that table (typically with a Flyway script); the lib does not
 * create it. Expected schema:
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS atomium_feed_pointer_v1 (
 *     feed_id                  text PRIMARY KEY,
 *     last_event_page_link     text,
 *     last_event_id            text,
 *     next_fetch_page_link text        NOT NULL,
 *     next_fetch_page_etag text,
 *     created_at               timestamptz NOT NULL,
 *     updated_at               timestamptz NOT NULL
 * );
 * }</pre>
 *
 * <p>Both the last processed event ({@code last_event_*}) and the next-fetch coordinate
 * ({@code next_fetch_*}) are stored. The fetch filter is not stored separately but derived (see the
 * {@link FeedPointer} constructor from the persisted fields).
 *
 * <p>The table version suffix ({@code _v1}) is deliberate: when the schema changes, the table name changes with it,
 * so that an app with an old table notices it right at startup (see {@link #verifySchema()}).
 */
class JdbcFeedPointerRepository implements FeedPointerRepository, InitializingBean {

    private static final String TABLE = "atomium_feed_pointer_v1";

    private final JdbcClient jdbcClient;

    JdbcFeedPointerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Verifies at startup that {@code atomium_feed_pointer_v1} exists. The application using the lib must
     * provide that table; if it is missing or is an old version, we want to know that right at startup instead of
     * only at the first feed run.
     */
    @Override
    public void afterPropertiesSet() {
        verifySchema();
    }

    /**
     * @throws IllegalStateException if the table is missing or does not have the expected schema.
     */
    void verifySchema() {
        try {
            // a SELECT on all expected columns fails (at parse time) when the table or a column is missing
            jdbcClient.sql("""
                    SELECT feed_id, last_event_page_link, last_event_id, next_fetch_page_link, next_fetch_page_etag, created_at, updated_at
                    FROM %s
                    WHERE false
                    """.formatted(TABLE))
                    .query((rs, rowNum) -> null)
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("The table '%s' is missing or does not have the expected schema. The application must provide this table (typically via a Flyway migration).".formatted(TABLE), e);
        }
    }

    @Override
    public Optional<FeedPointer> find(String feedId) {
        return jdbcClient.sql("""
                        SELECT last_event_page_link, last_event_id, next_fetch_page_link, next_fetch_page_etag
                        FROM %s
                        WHERE feed_id = :feedId
                        """.formatted(TABLE))
                .param("feedId", feedId)
                .query((rs, rowNum) -> new FeedPointer(
                        rs.getString("last_event_page_link"),
                        rs.getString("last_event_id"),
                        rs.getString("next_fetch_page_link"),
                        rs.getString("next_fetch_page_etag")))
                .optional();
    }

    @Override
    public void save(String feedId, FeedPointer feedPointer) {
        @Nullable EventCoordinate lastEvent = feedPointer.lastEvent();
        jdbcClient.sql("""
                        INSERT INTO %s (feed_id, last_event_page_link, last_event_id, next_fetch_page_link, next_fetch_page_etag, created_at, updated_at)
                        VALUES (:feedId, :lastEventPageLink, :lastEventId, :nextFetchPageLink, :nextFetchPageEtag, now(), now())
                        ON CONFLICT (feed_id) DO UPDATE SET
                            last_event_page_link     = excluded.last_event_page_link,
                            last_event_id            = excluded.last_event_id,
                            next_fetch_page_link = excluded.next_fetch_page_link,
                            next_fetch_page_etag = excluded.next_fetch_page_etag,
                            updated_at               = now()
                        """.formatted(TABLE))
                .param("feedId", feedId)
                .param("lastEventPageLink", lastEvent == null ? null : lastEvent.pageLink())
                .param("lastEventId", lastEvent == null ? null : lastEvent.eventId())
                .param("nextFetchPageLink", feedPointer.nextFetch().pageLink())
                .param("nextFetchPageEtag", feedPointer.nextFetch().etag())
                .update();
    }
}
