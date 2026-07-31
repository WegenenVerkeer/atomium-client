package be.wegenenverkeer.atomium.client.handler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test {@link FeedProcessor} with scripted answers, to drive the consumer through seam behavior the two real
 * processors never show — above all a processor that <em>declines</em> checkpoint opportunities. It records
 * every callback (including the {@link CheckpointReason} of each opportunity), so a test can assert exactly
 * which opportunities the consumer offered.
 */
class ScriptedFeedProcessor implements FeedProcessor<TestFeedEntry> {

    private final List<String> callbacks = new CopyOnWriteArrayList<>();
    private volatile Function<CheckpointReason, State> opportunityAnswer = reason -> State.IDLE;
    private volatile State entryAnswer = State.BUFFERING;
    private int accepted;
    private int processed;

    /** The answer to every {@code onEntry} (default {@link State#BUFFERING}). */
    void answerOnEntry(State answer) {
        this.entryAnswer = answer;
    }

    /** The answer to every checkpoint opportunity (default {@link State#IDLE}). */
    void answerOpportunities(Function<CheckpointReason, State> answer) {
        this.opportunityAnswer = answer;
    }

    @Override
    public State processEntry(ProcessingEntry<TestFeedEntry> entry) {
        callbacks.add("onEntry(%s)".formatted(entry.entry().id()));
        accepted++;
        return entryAnswer;
    }

    @Override
    public State onCheckpointOpportunity(CheckpointReason reason) {
        callbacks.add("opportunity(%s)".formatted(reason));
        return opportunityAnswer.apply(reason);
    }

    @Override
    public void persist() {
        callbacks.add("persist()");
        processed = accepted;
    }

    @Override
    public int accepted() {
        return accepted;
    }

    @Override
    public int processed() {
        return processed;
    }

    List<String> callbacks() {
        return callbacks;
    }
}
