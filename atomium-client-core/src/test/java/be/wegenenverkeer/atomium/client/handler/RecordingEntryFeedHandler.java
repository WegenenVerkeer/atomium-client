package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test {@link EntryFeedHandler} that records every callback in order, so a test can validate the exact
 * invocation sequence. Can deliberately fail on one entry ({@link #failAt}) to force the failure path
 * (rollback); the {@code accepts} filter is configurable per test ({@link #acceptOnly}).
 */
class RecordingEntryFeedHandler implements EntryFeedHandler<TestFeedEntry> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile @Nullable String failAtEntryId;
    private volatile Predicate<TestFeedEntry> accept = content -> true;

    /** Make {@code onEntry} throw on this entry, so a test can force the failure path (rollback). */
    void failAt(String entryId) {
        this.failAtEntryId = entryId;
    }

    /** Let through only the entries for which this holds (the {@code accepts} filter). */
    void acceptOnly(Predicate<TestFeedEntry> accept) {
        this.accept = accept;
    }

    @Override
    public String getFeedId() {
        return "feed";
    }

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, TestFeedEntry content) {
        return accept.test(content);
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, TestFeedEntry content) {
        if (entry.id().equals(failAtEntryId)) {
            throw new IllegalStateException("test handler fails deliberately on entry " + entry.id());
        }
        invocations.add("onEntry(%s, %s, %s)"
                .formatted(pageMetadata.href(FeedPageRel.SELF), entry.id(), content.aField()));
    }

    List<String> invocations() {
        return invocations;
    }

    void reset() {
        invocations.clear();
        failAtEntryId = null;
    }
}
