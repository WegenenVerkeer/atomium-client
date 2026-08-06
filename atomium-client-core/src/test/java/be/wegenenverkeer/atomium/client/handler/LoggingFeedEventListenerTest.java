package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.Link;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The level policy of the bundled logging listener: a quiet poll of an idle feed stays below INFO (only the
 * run start remains, the FeedRunner adds the completion line), while a run that actually delivers events is
 * fully visible at INFO.
 */
class LoggingFeedEventListenerTest {

    private final LoggingFeedEventListener listener = new LoggingFeedEventListener();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingFeedEventListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLog() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void aQuietPollLogsOnlyTheRunStartAtInfo() {
        listener.runStarted("feed", new FeedPointer("/504385/100"));
        listener.pageFetched("feed", page("/504385/100"), 0);
        listener.endOfFeedReached("feed");
        listener.feedNotModified("feed");
        listener.runCompleted("feed", new FeedRunResult(0, 0, 0));

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("feed 'feed': run started from page '/504385/100'");
        // the rest of the quiet poll remains available at DEBUG
        assertThat(appender.list).hasSize(5);
    }

    @Test
    void aRunThatDeliversEventsIsFullyVisibleAtInfo() {
        listener.pageFetched("feed", page("/504385/100"), 100);
        listener.runCompleted("feed", new FeedRunResult(100, 2, 2));

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "feed 'feed': page '/504385/100' fetched (100 entries)",
                        "feed 'feed': run completed; 100 read, 2 accepted, 2 processed");
    }

    private static FeedPageMetadata page(String self) {
        return new FeedPageMetadata(self, "http://localhost/feed", "feed", null, OffsetDateTime.now(),
                List.of(new Link("self", self)));
    }
}
