package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.ProcessResult;
import be.wegenenverkeer.atomium.client.handler.ProcessingEntry;
import be.wegenenverkeer.atomium.client.handler.SimpleBatchedProcessingFeedHandler;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Test base class for a {@link SimpleBatchedProcessingFeedHandler}: deduplicates on {@code aField} (the
 * "entity" of the test feed) in {@code process} — reporting its own processed count, like an application
 * would — and records every {@code process} and {@code persist} call as a single line. The prepared state
 * {@code P} is the formatted batch description, so a {@code persist(...)} line pins down in one assert what
 * was processed, in which order, and that {@code P} traveled intact between the two phases.
 *
 * <p>The <em>batch size</em> comes from the config ({@code processing.preferred-size}) and drives the
 * framework threshold — which doubles as proof that that property really works. The {@code accepts} filter and
 * deliberate failures in either phase ({@link #failInProcess()} / {@link #failInPersist()}) are configurable
 * per test.
 */
public abstract class RecordingBatchedFeedHandler
        implements SimpleBatchedProcessingFeedHandler<FooAppFeedEntry, String> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile Predicate<FooAppFeedEntry> accept = content -> true;
    private volatile boolean failInProcess;
    private volatile boolean failInPersist;

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, FooAppFeedEntry content) {
        return accept.test(content);
    }

    @Override
    public ProcessResult<String> process(List<ProcessingEntry<FooAppFeedEntry>> entries) {
        if (failInProcess) {
            throw new IllegalStateException("test handler fails deliberately in process");
        }
        invocations.add("process(%s)".formatted(show(entries)));
        // application-style dedup: last-wins per aField, first-seen order — so it reports its own count
        Map<String, ProcessingEntry<FooAppFeedEntry>> deduped = new LinkedHashMap<>();
        entries.forEach(entry -> deduped.put(entry.content().aField(), entry));
        List<ProcessingEntry<FooAppFeedEntry>> effective = List.copyOf(deduped.values());
        return ProcessResult.of(show(effective), effective.size());
    }

    @Override
    public void persist(String prepared) {
        if (failInPersist) {
            throw new IllegalStateException("test handler fails deliberately in persist");
        }
        invocations.add("persist(%s)".formatted(prepared));
    }

    /** Only lets through the entries for which this holds (the {@code accepts} filter). */
    public void acceptOnly(Predicate<FooAppFeedEntry> accept) {
        this.accept = accept;
    }

    public void failInProcess() {
        this.failInProcess = true;
    }

    public void failInPersist() {
        this.failInPersist = true;
    }

    /** Stop failing deliberately (the "recovered" half of a crash/retry scenario). */
    public void recover() {
        this.failInProcess = false;
        this.failInPersist = false;
    }

    public List<String> invocations() {
        return invocations;
    }

    public void reset() {
        invocations.clear();
        accept = content -> true;
        failInProcess = false;
        failInPersist = false;
    }

    private static String show(List<ProcessingEntry<FooAppFeedEntry>> entries) {
        return entries.stream()
                .map(entry -> "%s=%s".formatted(entry.entry().id(), entry.content().aField()))
                .collect(Collectors.joining(", "));
    }
}
