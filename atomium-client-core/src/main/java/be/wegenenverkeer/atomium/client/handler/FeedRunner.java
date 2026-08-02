package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of one feed around a {@link FeedConsumer}: scheduling triggers, start/stop, the guarantee
 * that no more than one run is ever in flight, and the <b>timing of the next run</b> (query interval on success,
 * backoff on consecutive failed runs). The reader itself remains a pure reader.
 *
 * <p>Two orthogonal states:
 * <ul>
 *   <li><b>active</b> — the <em>desired</em> state ({@code activeOnStartup} + later management tooling such as
 *       an admin endpoint). Determines whether a tick may start a run and serves as the stop signal.</li>
 *   <li><b>running</b> — the <em>actual</em> state: is a run in flight right now?</li>
 * </ul>
 * The combinations: inactive+standby (off), active+standby (waiting for a tick), active+running (busy),
 * inactive+running (transient "stopping" — the run stops after the next commit point). A separate
 * STOPPING status is therefore not needed.
 *
 * <p>{@link #tryToStart()} only starts a run if the feed is active, no run is in flight yet, <em>and</em> the
 * moment of the next run has been reached. Switching {@code running} false→true happens in one indivisible
 * step ({@link AtomicBoolean#compareAndSet}), so that a periodic tick and an admin trigger starting at the same time
 * still start at most one run between them. The run executes on the supplied {@link Executor} (in production a dedicated
 * thread per feed); {@code isInterrupted} is {@code () -> !active}, so that {@link #deactivate()} cleanly stops the
 * running run after the next commit point.
 *
 * <p><b>Timing (schedule-based, no sleeping thread):</b> the runner schedules nothing itself, but remembers after
 * every run the earliest moment of the next one ({@code nextRun}): after a successful run {@code now + queryInterval},
 * after a failed one {@code now + backoffPolicy.nextInterval(n)}. A scheduler ticks <em>frequently</em> (independent
 * of the query intervals; see {@code SimpleFeedScheduler}) and {@code tryToStart} refuses until that moment has been
 * reached — both the query interval and the backoff are thus respected at tick resolution. A successful run resets the
 * failure counter; {@link #activate()} also clears the wait time (a human who intervenes does not wait for a deadline).
 */
public final class FeedRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FeedRunner.class);

    private final String feedId;
    private final Duration queryInterval;
    private final FeedConsumer consumer;
    private final Executor executor;
    private final FeedBackoffPolicy backoffPolicy;
    private final Clock clock;
    private final FeedEventListener listeners;
    private final AtomicBoolean active;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // for deactivateAndAwait: the end of every run notifies; runThread exposes a call from the feed thread itself
    private final Object runStoppedMonitor = new Object();
    private volatile @Nullable Thread runThread;
    // the failure of the most recent run (null = healthy); for diagnostics such as a health indicator
    private volatile @Nullable FeedRunFailure lastFailure;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    // the earliest moment of the next run (null = right away); in the clock's zone, at second resolution
    private volatile @Nullable OffsetDateTime nextRun;

    public FeedRunner(String feedId, Duration queryInterval, FeedConsumer consumer, Executor executor,
                      boolean activeOnStartup, FeedBackoffPolicy backoffPolicy, Clock clock, FeedEventListener listeners) {
        this.feedId = feedId;
        this.queryInterval = queryInterval;
        this.consumer = consumer;
        this.executor = executor;
        this.backoffPolicy = backoffPolicy;
        this.clock = clock;
        this.listeners = listeners;
        this.active = new AtomicBoolean(activeOnStartup);
    }

    /**
     * Starts a run if the feed is active, no run is in flight yet and the moment of the next run
     * ({@link #nextRun()}) has been reached. Non-blocking: the actual run executes on the executor. Returns
     * {@code true} if this call started a run, {@code false} if there was nothing to do (inactive,
     * already busy, or the next run is not yet due).
     */
    public boolean tryToStart() {
        if (!active.get() || notYetDue() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(this::readUntilInterrupted);
        } catch (RuntimeException e) {
            running.set(false); // the submit failed → release the guard again, otherwise the feed stays "busy" forever
            throw e;
        }
        return true;
    }

    private boolean notYetDue() {
        OffsetDateTime next = nextRun;
        return next != null && OffsetDateTime.now(clock).isBefore(next);
    }

    private void readUntilInterrupted() {
        runThread = Thread.currentThread();
        try {
            // re-check 'active' before anything happens: a run that starts while the feed has just been deactivated
            // must not do any more work — after returning, deactivateAndAwait may count on "no more processing"
            if (active.get()) {
                consumer.run(() -> !active.get());
            }
            // an interrupted run (deactivation/shutdown) also returns without an exception, but is not a recovery or
            // poll moment: only a normally ended run (feed still active) resets the counter and schedules the next poll
            if (active.get()) {
                int previousFailures = consecutiveFailures.getAndSet(0);
                lastFailure = null;
                nextRun = afterNow(queryInterval);
                if (previousFailures > 0) {
                    LOG.info("feed '{}': recovered after {} consecutive failure(s)", feedId, previousFailures);
                }
                LOG.info("feed '{}': run completed; next run after {}", feedId, nextRun);
            }
        } catch (RuntimeException e) {
            recordFailure(e);
        } finally {
            runThread = null;
            running.set(false);
            synchronized (runStoppedMonitor) {
                runStoppedMonitor.notifyAll();
            }
        }
    }

    /** Increment the counter, compute the new backoff deadline, log (with entry context if present) and emit the event. */
    private void recordFailure(RuntimeException e) {
        int count = consecutiveFailures.incrementAndGet();
        OffsetDateTime next = afterNow(backoffPolicy.nextInterval(count));
        nextRun = next;

        String entryId = null;
        FeedEntryPhase phase = null;
        RuntimeException cause = e;
        if (e instanceof FeedEntryProcessingException entryFailure) {
            entryId = entryFailure.entryId();
            phase = entryFailure.phase();
            cause = entryFailure.cause();
        }

        if (entryId != null) {
            LOG.error("feed '{}': run failed at entry '{}' (phase {}); attempt {}, next attempt after {}; the scheduler retries",
                    feedId, entryId, phase, count, next, cause);
        } else {
            LOG.error("feed '{}': run failed; attempt {}, next attempt after {}; the scheduler retries",
                    feedId, count, next, cause);
        }
        FeedRunFailure failure = new FeedRunFailure(feedId, cause, count, next, entryId, phase);
        lastFailure = failure;
        listeners.runFailed(failure);
    }

    /** Now + the duration, at second resolution (fractional seconds are noise in logs and status). */
    private OffsetDateTime afterNow(Duration duration) {
        return OffsetDateTime.now(clock).plus(duration).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Mark the feed as desired-active (config startup or admin) and let the very next tick start right away. */
    public void activate() {
        scheduleNextRunNow();
        active.set(true);
        LOG.info("feed '{}': activated", feedId);
    }

    /**
     * Mark the feed as desired-inactive; a run in flight, if any, stops after the next commit point.
     * Non-blocking (and therefore also safe from a listener callback on the feed thread); if you need to know that
     * the run has actually stopped, use {@link #deactivateAndAwait}.
     */
    public void deactivate() {
        active.set(false);
        LOG.info("feed '{}': deactivated; a run in flight, if any, stops after the next commit point", feedId);
    }

    /**
     * {@link #deactivate() Deactivate} and wait until there is guaranteed to be no processing in flight anymore —
     * only then is it safe to e.g. move the feed pointer or do application cleanup. Returns {@code true} as soon as
     * no processing can happen until {@link #activate()} (a tick that starts a run at the very last moment sees the
     * deactivation before it does anything), {@code false} if the run in flight did not reach its commit point within
     * the timeout (the feed has then already been deactivated and still stops) or if the waiting thread was
     * interrupted (the interrupt status then remains set).
     *
     * @throws IllegalStateException from the feed thread itself (e.g. from a listener callback): there this would
     *                               deadlock — use {@link #deactivate()} there
     */
    public boolean deactivateAndAwait(Duration timeout) {
        if (Thread.currentThread() == runThread) {
            throw new IllegalStateException(("feed '%s': deactivateAndAwait must not be called from the feed thread "
                    + "itself (deadlock); use deactivate()").formatted(feedId));
        }
        deactivate();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        synchronized (runStoppedMonitor) {
            while (running.get()) {
                long remainingMillis = deadline - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    return false;
                }
                try {
                    runStoppedMonitor.wait(remainingMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Clear the wait time (query interval or backoff) and the failure counter: the very next
     * {@link #tryToStart()} may start a run right away. Invoked by {@link #activate()}.
     */
    public void scheduleNextRunNow() {
        consecutiveFailures.set(0);
        lastFailure = null;
        nextRun = null;
    }

    public boolean isActive() {
        return active.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** The number of consecutive failed runs since the last successful run/activation (0 = none). */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** The failure of the most recent run, or {@code null} as long as the feed is healthy. */
    public @Nullable FeedRunFailure lastFailure() {
        return lastFailure;
    }

    /**
     * The earliest moment of the next run: the next poll (query interval) or, after a failed run, the
     * backoff deadline. {@code null} = the very next {@link #tryToStart()} may start right away.
     */
    public @Nullable OffsetDateTime nextRun() {
        return nextRun;
    }

    public String feedId() {
        return feedId;
    }

    /** The poll frequency of this feed: the wait time between the end of a successful run and the next one. */
    public Duration queryInterval() {
        return queryInterval;
    }
}
