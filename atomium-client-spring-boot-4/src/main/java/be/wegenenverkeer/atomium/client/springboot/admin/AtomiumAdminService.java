package be.wegenenverkeer.atomium.client.springboot.admin;


import be.wegenenverkeer.atomium.client.exception.FeedStillRunningException;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties;
import be.wegenenverkeer.atomium.client.springboot.AtomiumProperties;
import be.wegenenverkeer.atomium.client.handler.EntryPusher;
import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * The diagnostics/troubleshooting operations behind the admin endpoint: reading and moving feed pointers,
 * reading lifecycle status and (de)activating, reading config and fetching raw feed pages. Leans on the
 * {@link Feeds} registry (the {@link FeedRuntime} per feed), the bound {@link AtomiumProperties} (config) and the
 * {@link FeedPointerRepository} (pointer persistence).
 */
public class AtomiumAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(AtomiumAdminService.class);

    private final Feeds feeds;
    private final FeedPointerRepository feedPointerRepository;
    private final AtomiumProperties atomiumProperties;

    public AtomiumAdminService(Feeds feeds, FeedPointerRepository feedPointerRepository,
                               AtomiumProperties atomiumProperties) {
        this.feeds = feeds;
        this.feedPointerRepository = feedPointerRepository;
        this.atomiumProperties = atomiumProperties;
    }

    /** The persisted read position of every feed (or {@code null} if there is none yet). */
    public List<FeedPointerDto> feedPointers() {
        return feeds.all().stream()
                .map(feed -> FeedPointerDto.of(feed.feedId(), feedPointerRepository.find(feed.feedId()).orElse(null)))
                .toList();
    }

    /**
     * The persisted read position of one feed (or {@code null} if there is none yet).
     *
     * @throws AtomiumUnknownFeedException if the feed does not exist
     */
    public FeedPointerDto feedPointer(String feedId) {
        runtime(feedId); // validates that the feed exists
        return FeedPointerDto.of(feedId, feedPointerRepository.find(feedId).orElse(null));
    }

    /**
     * Move the read position of a feed (troubleshooting): to the oldest page, to now, or to a known position
     * ({@link SetFeedPointerCommand}). The next run starts from there. Safe on an active feed, see
     * {@link FeedRuntime#whileStopped}.
     *
     * @return the pointer as persisted
     * @throws AtomiumUnknownFeedException     if the feed does not exist
     * @throws AtomiumAdminValidationException if the command is invalid
     * @throws FeedStillRunningException       from {@link FeedRuntime#whileStopped}
     */
    public FeedPointerDto setFeedPointer(String feedId, SetFeedPointerCommand command) {
        FeedRuntime runtime = runtime(feedId);
        FeedPointer pointer = switch (command.resolvedType()) {
            case OLDEST -> runtime.resetToOldest();
            case NOW -> runtime.resetFromNow();
            case POINTER -> runtime.moveFeedPointer(command.toFeedPointer());
        };
        return FeedPointerDto.of(feedId, pointer);
    }

    /** The lifecycle and backoff status of every feed. */
    public List<FeedStatusDto> statuses() {
        return feeds.all().stream()
                .map(feed -> status(feed.feedId(), feed.runner()))
                .toList();
    }

    /**
     * The lifecycle and backoff status of one feed.
     *
     * @throws AtomiumUnknownFeedException if the feed does not exist
     */
    public FeedStatusDto status(String feedId) {
        return status(feedId, runtime(feedId).runner());
    }

    private static FeedStatusDto status(String feedId, FeedRunner runner) {
        return new FeedStatusDto(feedId, runner.isActive(), runner.isRunning(),
                runner.consecutiveFailures(), runner.nextRun());
    }

    /**
     * @throws AtomiumUnknownFeedException  if the feed does not exist
     * @throws AtomiumAdminValidationException if the feed is already active
     */
    public void activate(String feedId) {
        FeedRunner runner = runtime(feedId).runner();
        if (runner.isActive()) {
            throw new AtomiumAdminValidationException(
                    "Only an inactive feed can be activated. Feed '%s' is already active.".formatted(feedId));
        }
        runner.activate();
        runner.tryToStart(); // start a run immediately instead of waiting for the next scheduler tick
    }

    /**
     * Deactivates and waits (bounded by the feed's {@code stopTimeout}) until a possibly running run has stopped,
     * so that application-level cleanup does not collide with a still-running run.
     *
     * @throws AtomiumUnknownFeedException  if the feed does not exist
     * @throws AtomiumAdminValidationException if the feed is already inactive
     */
    public void deactivate(String feedId) {
        FeedRuntime runtime = runtime(feedId);
        FeedRunner runner = runtime.runner();
        if (!runner.isActive()) {
            throw new AtomiumAdminValidationException(
                    "Only an active feed can be deactivated. Feed '%s' is already inactive.".formatted(feedId));
        }
        Duration stopTimeout = runtime.feed().stopTimeout();
        if (!runner.deactivateAndAwait(stopTimeout)) {
            LOG.warn("feed '{}': deactivated, but the running run had not stopped after {}; "
                    + "it will still stop by itself", feedId, stopTimeout);
        }
    }

    /** The resolved config of every feed. */
    public List<FeedConfigDto> config() {
        return feeds.all().stream()
                .map(feed -> new FeedConfigDto(feed.feedId(), properties(feed.feedId())))
                .toList();
    }

    /**
     * The resolved config of one feed.
     *
     * @throws AtomiumUnknownFeedException if the feed does not exist
     */
    public FeedConfigDto config(String feedId) {
        runtime(feedId); // validates that the feed exists
        return new FeedConfigDto(feedId, properties(feedId));
    }

    /**
     * Fetch a page of a feed <em>raw</em> (via {@link be.wegenenverkeer.atomium.client.fetch.AtomiumClient#fetchRawPage}):
     * the unmodified {@link FeedHttpClient.HttpResponse} (status, headers, body).
     *
     * @param pageLink the page href relative to the base url (e.g. {@code "/182"}); an empty string is the head
     * @throws AtomiumUnknownFeedException if the feed does not exist
     */
    public FeedHttpClient.HttpResponse rawPage(String feedId, String pageLink) {
        return runtime(feedId).feed().atomiumClient().fetchRawPage(pageLink);
    }

    /**
     * Process a raw content item (JSON) on a feed <em>as if</em> it appeared as an entry on the feed
     * (troubleshooting/repair). Delegates to the feed's {@link EntryPusher} (which decodes + calls the handler
     * within a transaction); the {@code AtomiumAdminService} itself knows neither the decoder nor the handler.
     *
     * @throws AtomiumUnknownFeedException  if the feed does not exist
     * @throws AtomiumAdminValidationException if the handler does not support push (it does not implement
     *                                        {@link be.wegenenverkeer.atomium.client.handler.FeedPusher})
     */
    public void pushEntry(String feedId, String rawContent) {
        try {
            runtime(feedId).pusher().pushEntry(rawContent);
        } catch (UnsupportedOperationException e) {
            throw new AtomiumAdminValidationException(
                    "Feed '%s' does not support push: the handler does not implement FeedPusher.".formatted(feedId));
        }
    }

    /** The bound config of the feed ({@code atomium.feeds.<feedId>}). */
    private AtomiumFeedProperties properties(String feedId) {
        AtomiumFeedProperties properties = atomiumProperties.feeds().get(feedId);
        if (properties == null) {
            // only possible on a programming error: the startup fail-fast guarantees config per registered feed
            throw new IllegalStateException("no config for registered feed '%s'".formatted(feedId));
        }
        return properties;
    }

    /** The feed runtime, or an {@link AtomiumUnknownFeedException} (→ 404) if the feed does not exist. */
    private FeedRuntime runtime(String feedId) {
        try {
            return feeds.get(feedId);
        } catch (IllegalArgumentException e) {
            throw new AtomiumUnknownFeedException("Unknown feed '%s'.".formatted(feedId));
        }
    }
}
