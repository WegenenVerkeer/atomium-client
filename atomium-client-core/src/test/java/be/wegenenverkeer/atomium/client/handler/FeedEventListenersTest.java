package be.wegenenverkeer.atomium.client.handler;


import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The hard rule that a {@link FeedEventListener} may never break a run: a throwing listener is caught in the
 * composite (WARN) and the remaining listeners still receive the event.
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

        FeedEventListeners composite = new FeedEventListeners(List.of(broken, good));

        assertThatCode(() -> composite.runStarted("feed", new FeedPointer("/0"))).doesNotThrowAnyException();
        assertThat(events).containsExactly("runStarted");
    }
}
