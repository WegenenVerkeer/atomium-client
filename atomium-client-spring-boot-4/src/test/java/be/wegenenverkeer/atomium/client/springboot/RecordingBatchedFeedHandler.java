package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.BatchedFeedHandler;
import be.wegenenverkeer.atomium.client.handler.DefaultFeedHandlerBatch;
import be.wegenenverkeer.atomium.client.handler.FeedHandlerBatch;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Test base class for a {@link BatchedFeedHandler}: deduplicates on {@code aField} (the "entity" of the
 * test feed) and records every {@code onBatch} as a single line, with the entries in processing order. That way a test
 * pins down in one assert what was flushed, in which order, and what the dedup left out.
 *
 * <p>The <em>batch size</em> comes from the config ({@code batch.preferred-batch-size}) and is simply passed
 * through here — which doubles as proof that that property really drives the threshold. The {@code accepts} filter is
 * configurable per test.
 */
public abstract class RecordingBatchedFeedHandler implements BatchedFeedHandler<FooAppFeedEntry> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile Predicate<FooAppFeedEntry> accept = content -> true;

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, FooAppFeedEntry content) {
        return accept.test(content);
    }

    @Override
    public FeedHandlerBatch<FooAppFeedEntry> startBatch(int preferredBatchSize) {
        return new DefaultFeedHandlerBatch<>(preferredBatchSize, FooAppFeedEntry::aField);
    }

    @Override
    public void onBatch(FeedHandlerBatch<FooAppFeedEntry> batch) {
        String entries = batch.getBuffer().stream()
                .map(batchEntry -> "%s=%s".formatted(batchEntry.entry().id(), batchEntry.content().aField()))
                .collect(Collectors.joining(", "));
        invocations.add("onBatch(%s)".formatted(entries));
    }

    /** Only lets through the entries for which this holds (the {@code accepts} filter). */
    public void acceptOnly(Predicate<FooAppFeedEntry> accept) {
        this.accept = accept;
    }

    public List<String> invocations() {
        return invocations;
    }

    public void reset() {
        invocations.clear();
        accept = content -> true;
    }
}
