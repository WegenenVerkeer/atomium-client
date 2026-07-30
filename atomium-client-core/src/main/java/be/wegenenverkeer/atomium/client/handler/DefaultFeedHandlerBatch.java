package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * The default {@link FeedHandlerBatch}: <b>deduplicates</b> on a key from the content and is complete as soon as
 * the batch contains a given number of <em>distinct keys</em>.
 *
 * <p>This is why batching pays off on a burst feed. Such a feed often contains many events about the same entity
 * ("A changed" ×5, "B" ×3, "C" ×1): that is nine events, but only three objects. Process them one by one and you
 * do the work eight times, only for it to be immediately overwritten. Collect them first, and you keep only the
 * <b>last</b> state per key and process three.
 *
 * <p>Semantics of {@link #getBuffer()}: per key the <em>last seen</em> {@link BatchEntry} (last-wins), and the
 * keys in the order in which they were <em>first</em> seen (a {@link LinkedHashMap} keeps its insertion order on
 * an overwrite). So: the youngest news, in the order of the feed.
 *
 * <p>The {@code keyExtractor} determines what "the same entity" means: usually
 * {@code content -> content.entityId()}. The default ({@code c -> c}) dedups on the content's own {@code equals},
 * which only merges identical events.
 *
 * <p><b>Note — dedup is per batch.</b> If the same entity occurs in two consecutive batches, it is processed
 * twice. That is deliberate: the processing should be idempotent, and a window across batch boundaries would
 * saddle the batch with crash recovery (that is a controller concern).
 *
 * @param <C> the domain type of the entry content
 * @param <K> the type of the dedup key
 */
// deliberately not final: a custom batch that refines this dedup variant is an intended extension point
public class DefaultFeedHandlerBatch<C, K> implements FeedHandlerBatch<C> {

    private final int preferredBatchSize;
    private final Function<C, K> keyExtractor;
    private final LinkedHashMap<K, BatchEntry<C>> batch = new LinkedHashMap<>();

    /**
     * @param preferredBatchSize the batch is complete as soon as it counts this many <em>distinct keys</em>
     * @param keyExtractor      what "the same entity" means; {@code c -> c} dedups on the content itself
     */
    public DefaultFeedHandlerBatch(int preferredBatchSize, Function<C, K> keyExtractor) {
        if (preferredBatchSize < 1) {
            throw new IllegalArgumentException("preferredBatchSize must be at least 1, was " + preferredBatchSize);
        }
        this.preferredBatchSize = preferredBatchSize;
        this.keyExtractor = keyExtractor;
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, C content) {
        // put() on an existing key replaces the value but keeps the original position: last-wins,
        // first-seen order
        batch.put(keyExtractor.apply(content), new BatchEntry<>(pageMetadata, entry, content));
    }

    /** Complete as soon as we have enough <em>distinct</em> keys — duplicates therefore do not fill up the batch. */
    @Override
    public boolean isComplete() {
        return batch.size() >= preferredBatchSize;
    }

    @Override
    public boolean isEmpty() {
        return batch.isEmpty();
    }

    @Override
    public List<BatchEntry<C>> getBuffer() {
        return List.copyOf(batch.values());
    }
}
