package be.wegenenverkeer.atomium.client.handler;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * <p>Three states, each answering its own question:
 * <ul>
 *   <li><b>active</b> — per feed: is this feed <em>wanted</em>? ({@code activeOnStartup} + later management
 *       tooling such as an admin endpoint.)</li>
 *   <li><b>enabled</b> — per runtime, for all feeds of this instance together: does <em>this instance</em>
 *       consume feeds? Owned by the {@link Feeds} registry and mirrored here ({@link #isEnabled()}): a runner
 *       starts disabled and is enabled by the registry it is added to (by default in the registry's constructor;
 *       later, through {@link Feeds#enable()} / {@link Feeds#disable()}, for a deployment where the consuming
 *       instance is chosen dynamically — leader election, a standby). A runner outside a registry never
 *       runs.</li>
 *   <li><b>running</b> — the <em>actual</em> state: is a run in flight right now?</li>
 * </ul>
 * A feed is consumed on this instance when it is {@code active && enabled}: the <b>effective</b> state. That is
 * what a tick starts, what the stop signal of a run in flight follows, and what the {@code feedActivated} /
 * {@code feedDeactivated} events report: they fire on a transition of the effective state, whichever of the two
 * flags flips it, so that a listener (a gauge, a health check) sees exactly whether this instance consumes the
 * feed. Switching {@code running} false→true happens in one indivisible step ({@link AtomicBoolean#compareAndSet}),
 * so that a periodic tick and an admin trigger starting at the same time still start at most one run between
 * them; inactive (or disabled) + running is the transient "stopping" — the run stops after the next commit point —
 * so a separate STOPPING status is not needed.
 *
 * <p>{@link #tryToStart()} only starts a run if the feed is effectively active, no run is in flight yet, <em>and</em>
 * the moment of the next run has been reached. The run executes on the supplied {@link Executor} (in production a
 * dedicated thread per feed); {@code isInterrupted} is {@code () -> !(active && enabled)}, so that
 * {@link #deactivate()} and {@link Feeds#disable()} cleanly stop the running run after the next commit point.
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
    // active and enabled change under stateLock, so that the effective state (active && enabled) transitions
    // exactly once per flip and the listeners get exactly one event per effective transition
    private final Object stateLock = new Object();
    private volatile boolean active;
    private volatile boolean enabled;
    // set once, by the Feeds registry: tells "disabled by my registry" (a standby) apart from "never in a
    // registry" (an assembly mistake, which tryToStart refuses loudly instead of silently never running)
    private volatile boolean registered;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // for deactivateAndAwait: the end of every run notifies; runThread exposes a call from the feed thread itself
    private final Object runStoppedMonitor = new Object();
    private volatile @Nullable Thread runThread;
    // the failure of the most recent run (null = healthy); for diagnostics such as a health indicator
    private volatile @Nullable FeedRunFailure lastFailure;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    // the earliest moment of the next run (null = right away); in the clock's zone, at second resolution
    private volatile @Nullable OffsetDateTime nextRun;

    /**
     * The runner starts disabled, whatever {@code activeOnStartup} says: the {@link Feeds} registry it is added to
     * enables it, and only then does an active feed emit {@code feedActivated} and start running.
     */
    public FeedRunner(String feedId, Duration queryInterval, FeedConsumer consumer, Executor executor,
                      boolean activeOnStartup, FeedBackoffPolicy backoffPolicy, Clock clock, FeedEventListener listeners) {
        this.feedId = feedId;
        this.queryInterval = queryInterval;
        this.consumer = consumer;
        this.executor = executor;
        this.backoffPolicy = backoffPolicy;
        this.clock = clock;
        this.listeners = listeners;
        this.active = false;
        this.enabled = false;
        if (activeOnStartup) {
            activate();
        }
    }

    /**
     * Starts a run if the feed is effectively active (active and the runtime enabled), no run is in flight yet
     * and the moment of the next run ({@link #nextRun()}) has been reached. Non-blocking: the actual run executes
     * on the executor. Returns {@code true} if this call started a run, {@code false} if there was nothing to do
     * (not consumed on this instance, already busy, or the next run is not yet due).
     *
     * @throws IllegalStateException if the runner is not in a {@link Feeds} registry: it would never run
     */
    public boolean tryToStart() {
        if (!registered) {
            throw new IllegalStateException(("feed '%s': the runner is not in a Feeds registry and would never run; "
                    + "wrap the runtime in new Feeds(List.of(runtime))").formatted(feedId));
        }
        if (!effectivelyActive() || notYetDue() || !running.compareAndSet(false, true)) {
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
            // re-check the effective state before anything happens: a run that starts while the feed has just been
            // deactivated (or the runtime disabled) must not do any more work — after returning, deactivateAndAwait
            // may count on "no more processing"
            if (effectivelyActive()) {
                consumer.run(() -> !effectivelyActive());
            }
            // an interrupted run (deactivation/disable/shutdown) also returns without an exception, but is not a
            // recovery or poll moment: only a normally ended run (feed still consumed) resets the counter and
            // schedules the next poll
            if (effectivelyActive()) {
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

    /**
     * A commit happened (reported by the runtime's commit listener): the feed is making progress, so the failure
     * streak is over. A run that commits work and then fails counts as attempt 1 again — a fresh backoff
     * episode — instead of escalating the backoff of failures it already recovered from.
     */
    void noteProgress() {
        int previousFailures = consecutiveFailures.getAndSet(0);
        if (previousFailures > 0) {
            lastFailure = null;
            LOG.info("feed '{}': recovered after {} consecutive failure(s)", feedId, previousFailures);
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
        synchronized (stateLock) {
            if (active) {
                return;
            }
            boolean wasConsumed = effectivelyActive();
            active = true;
            // on a disabled runtime the wanted state is not worth a line: Feeds logs the full picture when it
            // enables the feeds, and until then nothing changes here (the admin endpoint refuses)
            if (enabled) {
                LOG.info("feed '{}': activated", feedId);
            }
            emitEffectiveTransition(wasConsumed);
        }
    }

    /**
     * Mark the feed as desired-inactive; a run in flight, if any, stops after the next commit point.
     * Non-blocking (and therefore also safe from a listener callback on the feed thread); if you need to know that
     * the run has actually stopped, use {@link #deactivateAndAwait}.
     */
    public void deactivate() {
        synchronized (stateLock) {
            if (!active) {
                return;
            }
            boolean wasConsumed = effectivelyActive();
            active = false;
            if (enabled) {
                LOG.info("feed '{}': deactivated; a run in flight, if any, stops after the next commit point", feedId);
            }
            emitEffectiveTransition(wasConsumed);
        }
    }

    /**
     * The mirror of the runtime-wide switch of {@link Feeds}, set by the registry for every feed at once (invariant:
     * equal to {@code Feeds.isEnabled()} of the registry the runner belongs to). Enabling also clears the wait time,
     * like {@link #activate()}: an instance that takes over does not wait for a deadline from a previous episode.
     * Disabling stops a run in flight after its next commit point.
     */
    void setEnabled(boolean enabled) {
        registered = true;
        if (enabled) {
            scheduleNextRunNow();
        }
        synchronized (stateLock) {
            if (this.enabled == enabled) {
                return;
            }
            boolean wasConsumed = effectivelyActive();
            this.enabled = enabled;
            emitEffectiveTransition(wasConsumed);
        }
    }

    /** Under {@code stateLock}: one event per transition of the effective state, whichever flag flipped it. */
    private void emitEffectiveTransition(boolean wasConsumed) {
        boolean consumed = effectivelyActive();
        if (consumed && !wasConsumed) {
            listeners.feedActivated(feedId);
        } else if (!consumed && wasConsumed) {
            listeners.feedDeactivated(feedId);
        }
    }

    /** The effective state: the feed is consumed on this instance. */
    private boolean effectivelyActive() {
        return active && enabled;
    }

    /**
     * {@link #deactivate() Deactivate} and wait until there is guaranteed to be no processing in flight anymore —
     * only then is it safe to e.g. move the feed pointer or do application cleanup. Returns {@code true} as soon as
     * no processing can happen until {@link #activate()} (a tick that starts a run at the very last moment sees the
     * deactivation before it does anything), {@code false} if the run in flight did not reach its commit point within
     * the timeout (the feed has then already been deactivated and still stops) or if the waiting thread was
     * interrupted (the interrupt status then remains set). The timeout runs on the runner's clock, like every
     * time-based decision here.
     *
     * @throws IllegalStateException from the feed thread itself (e.g. from a listener callback): there this would
     *                               deadlock — use {@link #deactivate()} there
     */
    public boolean deactivateAndAwait(Duration timeout) {
        assertNotOnFeedThread("deactivateAndAwait", "deactivate()");
        deactivate();
        return awaitStopped(Instant.now(clock).plus(timeout));
    }

    /** The deadlock guard of the awaiting operations: from the feed thread itself they would wait on themselves. */
    void assertNotOnFeedThread(String operation, String alternative) {
        if (isFeedThread()) {
            throw new IllegalStateException(("feed '%s': %s must not be called from the feed thread itself "
                    + "(deadlock); use %s").formatted(feedId, operation, alternative));
        }
    }

    /**
     * Waits until no run is in flight anymore, at most until the deadline. The caller has already withdrawn the
     * feed's reason to run (deactivated it, or disabled the runtime), so a run that is not in flight now cannot
     * start anymore either.
     */
    boolean awaitStopped(Instant deadline) {
        synchronized (runStoppedMonitor) {
            while (running.get()) {
                long remainingMillis = Duration.between(Instant.now(clock), deadline).toMillis();
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

    /** Whether the calling thread is the feed thread of a run in flight (a handler or listener callback). */
    boolean isFeedThread() {
        return Thread.currentThread() == runThread;
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

    /** Whether the feed is wanted (per feed); whether it is consumed on this instance also needs {@link #isEnabled()}. */
    public boolean isActive() {
        return active;
    }

    /**
     * Whether the runtime of this instance consumes feeds: the mirror of {@link Feeds#isEnabled()}, the same for
     * every feed of the registry. A feed runs only when active and enabled.
     */
    public boolean isEnabled() {
        return enabled;
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
