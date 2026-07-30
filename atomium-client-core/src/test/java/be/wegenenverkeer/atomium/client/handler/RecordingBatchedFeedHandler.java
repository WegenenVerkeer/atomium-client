package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Test {@link BatchedFeedHandler}: deduplicates on {@code aField} (the "entity" of the test feed) and records
 * each {@code onBatch} as one line, with the entries in processing order. That lets a test pin down in a single
 * assert what was flushed, in which order, and what the dedup left out. The {@code accepts} filter is
 * configurable per test ({@link #acceptOnly}).
 */
class RecordingBatchedFeedHandler implements BatchedFeedHandler<TestFeedEntry> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile Predicate<TestFeedEntry> accept = content -> true;

    @Override
    public String getFeedId() {
        return "feed";
    }

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, TestFeedEntry content) {
        return accept.test(content);
    }

    @Override
    public FeedHandlerBatch<TestFeedEntry> startBatch(int preferredBatchSize) {
        return new DefaultFeedHandlerBatch<>(preferredBatchSize, TestFeedEntry::aField);
    }

    @Override
    public void onBatch(FeedHandlerBatch<TestFeedEntry> batch) {
        String entries = batch.getBuffer().stream()
                .map(batchEntry -> "%s=%s".formatted(batchEntry.entry().id(), batchEntry.content().aField()))
                .collect(Collectors.joining(", "));
        invocations.add("onBatch(%s)".formatted(entries));
    }

    /** Let through only the entries for which this holds (the {@code accepts} filter). */
    void acceptOnly(Predicate<TestFeedEntry> accept) {
        this.accept = accept;
    }

    List<String> invocations() {
        return invocations;
    }
}
