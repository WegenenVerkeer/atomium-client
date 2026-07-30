package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.util.List;

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

    /** Run {@code action} once, immediately after the event {@code trigger} (e.g. {@code "entriesProcessed(id-002)"}). */
    public void interruptAfter(String trigger, Runnable action) {
        this.trigger = trigger;
        this.action = action;
    }

    @Override
    public void entriesProcessed(String feedId, List<? extends BatchEntry<?>> entries) {
        entries.forEach(batchEntry -> fireIfTriggered("entriesProcessed(%s)".formatted(batchEntry.entry().id())));
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
