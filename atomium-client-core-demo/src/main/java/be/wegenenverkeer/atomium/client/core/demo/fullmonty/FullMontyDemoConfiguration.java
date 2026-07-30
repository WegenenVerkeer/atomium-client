package be.wegenenverkeer.atomium.client.core.demo.fullmonty;

import be.wegenenverkeer.atomium.client.core.demo.DemoAtomiumClients;
import be.wegenenverkeer.atomium.client.core.demo.DemoProperties;
import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.handler.BatchEntry;
import be.wegenenverkeer.atomium.client.handler.ExponentialFeedBackoffPolicy;
import be.wegenenverkeer.atomium.client.handler.Feed;
import be.wegenenverkeer.atomium.client.handler.FeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedRunResult;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.LoggingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.PerFeedThreadExecutors;
import be.wegenenverkeer.atomium.client.jackson.JacksonFeedContentDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The <b>complete</b> assembly of the handler API: a single {@code Feed} that explicitly provides <em>every</em>
 * building block and <em>every</em> knob of the builder — the mirror image of the minimal
 * {@code SimpleDemoConfiguration}. The implementations are deliberately thin; the point is to show which
 * building blocks exist and where they plug in. (The builder's two batch knobs belong to a
 * {@code BatchedFeedHandler} — see the {@code simple-batched} assembly.)
 */
@Configuration
class FullMontyDemoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FullMontyDemoConfiguration.class);

    /** The per-feed daemon-thread executors; as a bean so the threads are shut down cleanly on shutdown. */
    @Bean(destroyMethod = "shutdown")
    PerFeedThreadExecutors perFeedThreadExecutors() {
        return new PerFeedThreadExecutors();
    }

    @Bean
    FeedRuntime fullMontyFeedRuntime(FullMontyFeedHandler handler, JsonMapper jsonMapper, DemoProperties properties,
                                     JdbcClient jdbcClient, PlatformTransactionManager transactionManager,
                                     PerFeedThreadExecutors executors) {
        String feedId = handler.getFeedId();
        // the fetch substrate: FeedHttpClient (restclient adapter) + FeedPageDecoder (jackson-3 adapter)
        AtomiumClient atomiumClient = DemoAtomiumClients.atomiumClient(feedId, properties.feedUrl());

        Feed<MontyContent> feed = Feed
                // the four parameters without a default: feedId, handler, client, content decoder
                // (the content decoder comes from jackson-3 and derives MontyContent from the handler type)
                .builder(feedId, handler, atomiumClient, JacksonFeedContentDecoder.of(handler, jsonMapper))
                // real pointer persistence instead of the in-memory default: crash/restart-safe resumption
                .pointerRepository(new DemoJdbcFeedPointerRepository(jdbcClient))
                // real transactions instead of the no-op default: commit handler effect + pointer atomically
                .transactions(new SpringTransactionFeedTransactions(new TransactionTemplate(transactionManager)))
                // the start position for a brand-new feed; alternatives: now, or an explicit page
                .initialFeedPointer(atomiumClient::pointerToOldest)
                // the backoff on consecutive failed runs (default: 1m → 1h, ×2; shorter here for the demo)
                .backoffPolicy(new ExponentialFeedBackoffPolicy(
                        Duration.ofSeconds(5), Duration.ofMinutes(5), 2))
                // the executor the runs execute on (default: a dedicated daemon thread; here the managed variant)
                .executor(executors.executorFor(feedId))
                // listeners are additive: the bundled logging listener plus a custom instance
                .addListener(new LoggingFeedEventListener())
                .addListener(countingListener(feedId))
                // the poll frequency and whether the feed is active right away
                .queryInterval(properties.queryInterval())
                .activeOnStartup(properties.fullMonty().activeOnStartup())
                .build();
        return FeedRuntime.of(feed);
    }

    /** A custom {@link FeedEventListener}: counts the processed events (metrics/health/alerting follow this pattern). */
    private static FeedEventListener countingListener(String feedId) {
        AtomicLong total = new AtomicLong();
        return new FeedEventListener() {
            @Override
            public void entriesProcessed(String id, List<? extends BatchEntry<?>> entries) {
                LOG.info("demo-listener: feed '{}' is at {} processed event(s)",
                        feedId, total.addAndGet(entries.size()));
            }

            @Override
            public void feedPointerAdvanced(String id, FeedPointer feedPointer, FeedRunResult sincePreviousCommit) {
                LOG.debug("demo-listener: feed '{}' committed at {}", feedId, feedPointer);
            }
        };
    }
}
