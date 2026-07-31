package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * Test {@link FeedEventListener} with which a test can interrupt a running run <em>at an exact point</em>.
 *
 * <p>This works precisely because the consumer consults {@code isInterrupted} ({@code = !runner.active}) after each
 * commit point, and the listener callbacks sit right before that: a {@code runner.deactivate()} from this listener
 * thus lands exactly between "commit done" and "should we stop?". That forces an interruption without threads or
 * timing — and without mockito.
 */
public class InterruptingFeedEventListener implements FeedEventListener {

    private @Nullable String trigger;
    private @Nullable Runnable action;

    /**
     * Run {@code action} once, immediately after the event {@code trigger}: {@code "committed(id-002)"} (the
     * commit whose pointer sits at that event) or {@code "pageProcessed(/0)"}.
     */
    public void interruptAfter(String trigger, Runnable action) {
        this.trigger = trigger;
        this.action = action;
    }

    @Override
    public void feedPointerAdvanced(String feedId, FeedPointer feedPointer, FeedRunResult sincePreviousCommit,
                                    @Nullable OffsetDateTime latestEventUpdated) {
        EventCoordinate lastEvent = feedPointer.lastEvent();
        fireIfTriggered("committed(%s)".formatted(lastEvent == null ? "-" : lastEvent.eventId()));
    }

    @Override
    public void pageProcessed(String feedId, FeedPageMetadata pageMetadata) {
        fireIfTriggered("pageProcessed(%s)".formatted(pageMetadata.href(FeedPageRel.SELF)));
    }

    private void fireIfTriggered(String event) {
        if (event.equals(trigger)) {
            Runnable toRun = action;
            reset();   // one-shot: the next run must not be interrupted again
            if (toRun != null) {
                toRun.run();
            }
        }
    }

    public void reset() {
        trigger = null;
        action = null;
    }
}
