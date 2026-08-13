package be.wegenenverkeer.atomium.client.handler;


import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static ch.qos.logback.classic.Level.ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The hard rule that a {@link FeedEventListener} may never break a run: a throwing listener is caught in the
 * composite — logged at ERROR, because a throwing listener is always unexpected — and the remaining listeners
 * still receive the event.
 */
class FeedEventListenersTest {

    @Test
    void aThrowingListenerDoesNotBreakTheDispatchAndTheOthersStillReceiveTheEvent() {
        FeedEventListener broken = new FeedEventListener() {
            @Override
            public void runStarted(String feedId, FeedPointer startPosition) {
                throw new RuntimeException("boom");
            }
        };
        List<String> events = new ArrayList<>();
        FeedEventListener good = new FeedEventListener() {
            @Override
            public void runStarted(String feedId, FeedPointer startPosition) {
                events.add("runStarted");
            }
        };
        ListAppender<ILoggingEvent> log = captureCompositeLog();

        FeedEventListeners composite = new FeedEventListeners(List.of(broken, good));

        assertThatCode(() -> composite.runStarted("feed", new FeedPointer("/0"))).doesNotThrowAnyException();
        assertThat(events).containsExactly("runStarted");
        assertThat(log.list).singleElement().satisfies(line -> assertThat(line.getLevel()).isEqualTo(ERROR));
    }

    private static ListAppender<ILoggingEvent> captureCompositeLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(FeedEventListeners.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
