package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.handler.RecordingFeedEventListener;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test base class that records every handler callback in order, so a test can validate the exact invocation
 * sequence. Concrete subclasses are {@code @Component}s with their own {@code feedId}.
 *
 * <p>Only the <em>entry</em> callbacks live here: the lifecycle (page boundary, end of feed, interruption) is not
 * a concern of the handler SPI — it is observed via the {@link RecordingFeedEventListener}.
 *
 * <p>Thread-safe ({@link CopyOnWriteArrayList}): in the scheduler IT the feed thread writes while the
 * test thread reads concurrently.
 *
 * <p>Push is deliberately <em>not</em> supported here (this class does not implement
 * {@link be.wegenenverkeer.atomium.client.handler.FeedPusher}); a subclass that wants to test push
 * ({@link FooAppFeedHandler}) implements it itself.
 */
public abstract class RecordingFeedHandler implements EntryFeedHandler<FooAppFeedEntry> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile @Nullable String failAtEntryId;

    /** Makes {@code onEntry} throw on this entry, so a test can force the failure path (rollback). */
    public void failAt(String entryId) {
        this.failAtEntryId = entryId;
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, FooAppFeedEntry content) {
        if (entry.id().equals(failAtEntryId)) {
            throw new IllegalStateException("test handler fails deliberately on entry " + entry.id());
        }
        invocations.add("onEntry(%s, %s, %s)".formatted(page(pageMetadata), entry.id(), content.aField()));
    }

    /** Records a pushed content item; only usable by subclasses that implement FeedPusher. */
    protected void recordPush(FooAppFeedEntry content) {
        invocations.add("pushEntry(%s)".formatted(content.aField()));
    }

    public List<String> invocations() {
        return invocations;
    }

    public void reset() {
        invocations.clear();
        failAtEntryId = null;
    }

    private static String page(FeedPageMetadata pageMetadata) {
        return pageMetadata.href(FeedPageRel.SELF);
    }
}
