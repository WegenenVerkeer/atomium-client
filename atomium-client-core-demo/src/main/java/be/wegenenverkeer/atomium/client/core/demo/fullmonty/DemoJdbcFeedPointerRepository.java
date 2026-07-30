package be.wegenenverkeer.atomium.client.core.demo.fullmonty;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/**
 * A real (JDBC) implementation of the {@link FeedPointerRepository} building block: persists the
 * {@link FeedPointer} per feed in the table {@code demo_feed_pointer} (H2; see {@code schema.sql}). The stored fields are
 * the last processed event ({@code last_event_*}, {@code null} before the first event) and the next-fetch
 * coordinate ({@code next_fetch_*}); the fetch filter is not stored but derived by the {@link FeedPointer}
 * constructor. This is the pattern every stack with its own persistence follows.
 */
class DemoJdbcFeedPointerRepository implements FeedPointerRepository {

    private final JdbcClient jdbcClient;

    DemoJdbcFeedPointerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<FeedPointer> find(String feedId) {
        return jdbcClient.sql("""
                        SELECT last_event_page_link, last_event_id, next_fetch_page_link, next_fetch_page_etag
                        FROM demo_feed_pointer
                        WHERE feed_id = :feedId
                        """)
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
        EventCoordinate lastEvent = feedPointer.lastEvent();
        jdbcClient.sql("""
                        MERGE INTO demo_feed_pointer (feed_id, last_event_page_link, last_event_id,
                                                      next_fetch_page_link, next_fetch_page_etag)
                        KEY (feed_id)
                        VALUES (:feedId, :lastEventPageLink, :lastEventId, :nextFetchPageLink, :nextFetchPageEtag)
                        """)
                .param("feedId", feedId)
                .param("lastEventPageLink", lastEvent == null ? null : lastEvent.pageLink())
                .param("lastEventId", lastEvent == null ? null : lastEvent.eventId())
                .param("nextFetchPageLink", feedPointer.nextFetch().pageLink())
                .param("nextFetchPageEtag", feedPointer.nextFetch().etag())
                .update();
    }
}
