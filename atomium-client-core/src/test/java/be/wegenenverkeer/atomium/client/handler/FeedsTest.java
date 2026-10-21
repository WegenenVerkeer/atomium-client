package be.wegenenverkeer.atomium.client.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The {@link Feeds} registry fails fast on a duplicate feedId (a classic copy-paste mistake in
 * {@code getFeedId()}) and, on an unknown feedId, names the feeds that <em>are</em> registered. It also carries
 * the runtime-wide {@code enabled} switch and fans it out to every feed.
 */
class FeedsTest {

    @Test
    void aDuplicateFeedIdFailsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new Feeds(List.of(feed("feed-a"), feed("feed-a"))))
                .withMessageContaining("duplicate feedId")
                .withMessageContaining("feed-a");
    }

    @Test
    void anUnknownFeedIdNamesTheKnownFeeds() {
        Feeds feeds = new Feeds(List.of(feed("feed-a"), feed("feed-b")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> feeds.get("feed-typo"))
                .withMessageContaining("feed-typo")
                .withMessageContaining("feed-a")
                .withMessageContaining("feed-b");
    }

    @Nested
    class RuntimeEnabled {

        private final AtomicInteger runsA = new AtomicInteger();
        private final AtomicInteger runsB = new AtomicInteger();

        /** The registry owns the switch and mirrors it onto its runners, which start disabled. */
        @Test
        void anEnabledRegistryEnablesItsFeedsAtConstruction() {
            FeedRunner feedA = runner("feed-a", counting(runsA));
            assertThat(feedA.isEnabled()).isFalse();

            Feeds feeds = new Feeds(List.of(TestFeedRuntimes.withRunnerOnly(feedA)));

            assertThat(feeds.isEnabled()).isTrue();
            assertThat(feedA.isEnabled()).isTrue();
            assertThat(feedA.tryToStart()).isTrue();
        }

        @Test
        void aDisabledRegistryLeavesItsFeedsDisabledUntilEnabled() {
            FeedRunner feedA = runner("feed-a", counting(runsA));
            FeedRunner feedB = runner("feed-b", counting(runsB));
            Feeds feeds = new Feeds(
                    List.of(TestFeedRuntimes.withRunnerOnly(feedA), TestFeedRuntimes.withRunnerOnly(feedB)), false);
            assertThat(feeds.isEnabled()).isFalse();
            assertThat(feedA.tryToStart()).isFalse();

            feeds.enable();

            assertThat(feeds.isEnabled()).isTrue();
            assertThat(feedA.isEnabled()).isTrue();
            assertThat(feedB.isEnabled()).isTrue();
            assertThat(feedA.tryToStart()).isTrue();
            assertThat(feedB.tryToStart()).isTrue();

            feeds.disable();

            assertThat(feeds.isEnabled()).isFalse();
            assertThat(feedA.isEnabled()).isFalse();
            assertThat(feedB.isEnabled()).isFalse();
            assertThat(feedA.isActive()).isTrue();    // the feeds stay wanted
        }

        @Test
        void disableAndAwaitReturnsOnceNoRunIsInFlightAnymore() throws Exception {
            CountDownLatch runBusy = new CountDownLatch(1);
            FeedConsumer spinning = isInterrupted -> {
                runBusy.countDown();
                while (!isInterrupted.getAsBoolean()) {
                    Thread.onSpinWait();
                }
            };
            ExecutorService feedThread = Executors.newSingleThreadExecutor();
            try {
                FeedRunner busy = runner("feed-a", spinning, feedThread);
                FeedRunner idle = runner("feed-b", counting(runsB));
                Feeds feeds = new Feeds(List.of(TestFeedRuntimes.withRunnerOnly(busy), TestFeedRuntimes.withRunnerOnly(idle)));
                assertThat(busy.tryToStart()).isTrue();
                assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();

                assertThat(feeds.disableAndAwait(Duration.ofSeconds(2))).isTrue();

                assertThat(busy.isRunning()).isFalse();
                assertThat(feeds.isEnabled()).isFalse();
                assertThat(busy.isActive()).isTrue();     // still wanted
                assertThat(busy.tryToStart()).isFalse();
            } finally {
                feedThread.shutdownNow();
            }
        }

        @Test
        void disableAndAwaitReturnsFalseWhenARunDoesNotStopInTime() throws Exception {
            CountDownLatch runBusy = new CountDownLatch(1);
            CountDownLatch mayFinish = new CountDownLatch(1);
            FeedConsumer stuck = isInterrupted -> {
                runBusy.countDown();
                try {
                    mayFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            ExecutorService feedThread = Executors.newSingleThreadExecutor();
            try {
                FeedRunner busy = runner("feed-a", stuck, feedThread);
                Feeds feeds = new Feeds(List.of(TestFeedRuntimes.withRunnerOnly(busy)));
                assertThat(busy.tryToStart()).isTrue();
                assertThat(runBusy.await(2, TimeUnit.SECONDS)).isTrue();

                assertThat(feeds.disableAndAwait(Duration.ofMillis(100))).isFalse();

                assertThat(feeds.isEnabled()).isFalse();   // the disable itself sticks
                assertThat(busy.isRunning()).isTrue();
            } finally {
                mayFinish.countDown();
                feedThread.shutdownNow();
            }
        }

        /** The per-feed startup signal belongs to the moment the feeds start to run: enabling, not scheduling. */
        @Test
        void enablingLogsEveryFeedOnceActiveAsInfoInactiveAsWarn() {
            FeedRunner active = runner("feed-a", counting(runsA));
            FeedRunner inactive = runner("feed-b", counting(runsB));
            inactive.deactivate();
            ListAppender<ILoggingEvent> log = captureFeedsLog();
            try {
                Feeds feeds = new Feeds(
                        List.of(TestFeedRuntimes.withRunnerOnly(active), TestFeedRuntimes.withRunnerOnly(inactive)), false);
                assertThat(log.list).noneMatch(event -> event.getLevel() == Level.WARN);

                feeds.enable();

                assertThat(log.list)
                        .anyMatch(event -> event.getLevel() == Level.INFO
                                && event.getFormattedMessage().startsWith("feed 'feed-a' active"))
                        .anyMatch(event -> event.getLevel() == Level.WARN
                                && event.getFormattedMessage().startsWith("feed 'feed-b' is INACTIVE"));
            } finally {
                ((Logger) LoggerFactory.getLogger(Feeds.class)).detachAppender(log);
            }
        }

        private static ListAppender<ILoggingEvent> captureFeedsLog() {
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            ((Logger) LoggerFactory.getLogger(Feeds.class)).addAppender(appender);
            return appender;
        }

        @Test
        void anEmptyRegistryCarriesTheSwitchAllTheSame() {
            assertThat(new Feeds(List.of()).isEnabled()).isTrue();
            assertThat(new Feeds(List.of(), false).isEnabled()).isFalse();
        }
    }

    private static FeedRuntime feed(String feedId) {
        return TestFeedRuntimes.withRunnerOnly(runner(feedId, isInterrupted -> {
        }));
    }

    /** An active feed on an inline executor; whether it runs depends on the switch under test. */
    private static FeedRunner runner(String feedId, FeedConsumer consumer) {
        return runner(feedId, consumer, Runnable::run);
    }

    private static FeedRunner runner(String feedId, FeedConsumer consumer, Executor executor) {
        return new FeedRunner(feedId, Duration.ofMinutes(1), consumer, executor, true,
                n -> Duration.ofMinutes(1), Clock.systemUTC(), new FeedEventListener() {
        });
    }

    private static FeedConsumer counting(AtomicInteger counter) {
        return isInterrupted -> counter.incrementAndGet();
    }
}
