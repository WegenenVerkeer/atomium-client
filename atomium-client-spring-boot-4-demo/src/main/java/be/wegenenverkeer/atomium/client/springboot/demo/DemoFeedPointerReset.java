package be.wegenenverkeer.atomium.client.springboot.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The {@link DemoFeedEndpoint} keeps its entries <em>in memory</em> and starts over with an empty list on every
 * start. The persisted feed pointer (postgres, survives between runs) would otherwise point to a page of a
 * previous run. That is why we clear the pointer on startup, so the consumer cleanly restarts from the oldest page
 * every time. This happens in the constructor (at bean creation, so before the {@code FeedScheduler} starts).
 */
@Component
class DemoFeedPointerReset {

    private static final Logger LOG = LoggerFactory.getLogger(DemoFeedPointerReset.class);

    DemoFeedPointerReset(JdbcClient jdbcClient) {
        int cleared = jdbcClient.sql("DELETE FROM atomium_feed_pointer_v1").update();
        LOG.info("demo: {} persisted feed pointer(s) cleared at startup (in-memory feed starts empty)", cleared);
    }
}
