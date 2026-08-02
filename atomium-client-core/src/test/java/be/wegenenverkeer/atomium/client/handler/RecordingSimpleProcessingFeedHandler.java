package be.wegenenverkeer.atomium.client.handler;

import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Test {@link SimpleProcessingFeedHandler}: records each {@code process} and {@code persist} call as one
 * line. The prepared state {@code P} is the formatted batch description, so a {@code persist(...)} line proves in a
 * single assert both <em>what</em> was batched and that {@code P} traveled intact from {@code process} to
 * {@code persist}.
 *
 * <p>Configurable per test: the {@code accepts} filter ({@link #acceptOnly}), an application-style dedup on
 * {@code aField} that reports its own processed count ({@link #dedupeOnAField()}), deliberate failures in either
 * phase ({@link #failInProcess()} / {@link #failInPersist()}), transaction-scope recording
 * ({@link #recordTransactionScope}) and a hook that runs when a given entry is offered to {@code accepts}
 * ({@link #whenOffered} — handy to force an interruption mid-page).
 */
class RecordingSimpleProcessingFeedHandler implements SimpleProcessingFeedHandler<TestFeedEntry, String> {

    private final List<String> invocations = new CopyOnWriteArrayList<>();
    private volatile Predicate<TestFeedEntry> accept = content -> true;
    private volatile boolean dedupeOnAField;
    private volatile boolean failInProcess;
    private volatile boolean failInPersist;
    private volatile @Nullable RecordingFeedTransactions transactionScope;
    private volatile @Nullable String offeredTrigger;
    private volatile @Nullable Runnable offeredAction;

    @Override
    public String getFeedId() {
        return "feed";
    }

    @Override
    public boolean accepts(FeedPageMetadata pageMetadata, AtomiumEntry entry, TestFeedEntry content) {
        if (entry.id().equals(offeredTrigger)) {
            Runnable action = offeredAction;
            offeredTrigger = null;
            if (action != null) {
                action.run();
            }
        }
        return accept.test(content);
    }

    @Override
    public ProcessResult<String> process(List<ProcessingEntry<TestFeedEntry>> entries) {
        if (failInProcess) {
            throw new IllegalStateException("test handler fails deliberately in process");
        }
        invocations.add("process(%s)%s".formatted(show(entries), transactionSuffix()));
        if (!dedupeOnAField) {
            return ProcessResult.of(show(entries));
        }
        // application-style dedup: last-wins per aField, first-seen order — and so it reports its own count
        Map<String, ProcessingEntry<TestFeedEntry>> deduped = new LinkedHashMap<>();
        entries.forEach(entry -> deduped.put(entry.content().aField(), entry));
        List<ProcessingEntry<TestFeedEntry>> effective = List.copyOf(deduped.values());
        return ProcessResult.of(show(effective), effective.size());
    }

    @Override
    public void persist(String prepared) {
        if (failInPersist) {
            throw new IllegalStateException("test handler fails deliberately in persist");
        }
        invocations.add("persist(%s)%s".formatted(prepared, transactionSuffix()));
    }

    /** Let through only the entries for which this holds (the {@code accepts} filter). */
    void acceptOnly(Predicate<TestFeedEntry> accept) {
        this.accept = accept;
    }

    /** Deduplicate on {@code aField} in {@code process} and report the deduplicated count as {@code processed}. */
    void dedupeOnAField() {
        this.dedupeOnAField = true;
    }

    void failInProcess() {
        this.failInProcess = true;
    }

    void failInPersist() {
        this.failInPersist = true;
    }

    /** Append {@code [inTransaction=…]} to every process/persist line, so a test can assert the phase scopes. */
    void recordTransactionScope(RecordingFeedTransactions transactions) {
        this.transactionScope = transactions;
    }

    /** Run {@code action} once, at the moment the entry with this id is offered to {@code accepts}. */
    void whenOffered(String entryId, Runnable action) {
        this.offeredAction = action;
        this.offeredTrigger = entryId;
    }

    List<String> invocations() {
        return invocations;
    }

    private String transactionSuffix() {
        RecordingFeedTransactions transactions = transactionScope;
        return transactions == null ? "" : " [inTransaction=%s]".formatted(transactions.isInTransaction());
    }

    private static String show(List<ProcessingEntry<TestFeedEntry>> entries) {
        return entries.stream()
                .map(entry -> "%s=%s".formatted(entry.entry().id(), entry.content().aField()))
                .collect(Collectors.joining(", "));
    }
}
