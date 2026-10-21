package be.wegenenverkeer.atomium.client.core.demo;

import be.wegenenverkeer.atomium.client.exception.FeedStillRunningException;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control of the handler feeds during the demo: view status, activate/deactivate (the {@link FeedRunner}), enable
 * or disable the runtime as a whole ({@link Feeds#enable()} / {@link Feeds#disable()}: what a leader election
 * would call), reset the read position ({@link FeedRuntime#resetToOldest} / {@link FeedRuntime#resetFromNow}) and
 * push a standalone content item (the {@code EntryPusher}). This is what the Spring Boot module's admin endpoint
 * offers out of the box; a core user writes it in this shape themselves — the building blocks expose
 * everything needed for that.
 */
@RestController
@RequestMapping("/rest/demo/feeds")
class DemoControlEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(DemoControlEndpoint.class);

    private final Feeds feeds;

    DemoControlEndpoint(Feeds feeds) {
        this.feeds = feeds;
    }

    /** The runtime switch and the lifecycle and backoff status of every feed. */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", feeds.isEnabled());
        status.put("feeds", feeds.all().stream().map(DemoControlEndpoint::status).toList());
        return status;
    }

    /** This instance consumes feeds again: every active feed resumes at the next tick. */
    @PutMapping("/enable")
    public Map<String, Object> enable() {
        LOG.info("demo: enable the runtime");
        feeds.enable();
        return status();
    }

    /** This instance stops consuming feeds (a standby): runs in flight stop after their next commit point. */
    @PutMapping("/disable")
    public Map<String, Object> disable() {
        LOG.info("demo: disable the runtime");
        feeds.disable();
        return status();
    }

    /** Activates the feed and triggers a run right away (instead of waiting for the next scheduler tick). */
    @PutMapping("/{feedId}/activate")
    public Map<String, Object> activate(@PathVariable("feedId") String feedId) {
        LOG.info("demo: activate feed '{}'", feedId);
        FeedRuntime feed = feeds.get(feedId);
        feed.runner().activate();
        boolean started = feed.runner().tryToStart();
        Map<String, Object> status = status(feed);
        status.put("started", started);
        return status;
    }

    /** Deactivates the feed; a run in progress stops after the next commit point. */
    @PutMapping("/{feedId}/deactivate")
    public Map<String, Object> deactivate(@PathVariable("feedId") String feedId) {
        LOG.info("demo: deactivate feed '{}'", feedId);
        FeedRuntime feed = feeds.get(feedId);
        feed.runner().deactivate();
        return status(feed);
    }

    /**
     * Moves the read position of the feed to the oldest page ({@code ?to=oldest}: the full history again) or to now
     * ({@code ?to=now}: skip the backlog). Safe on an active feed: the runtime stops it, waits for the run in flight,
     * moves the pointer and activates it again — the next run then starts from the new position. A run that does
     * not stop within the feed's stop timeout gives 409 ({@link #onFeedStillRunning}), nothing changed.
     */
    @PutMapping("/{feedId}/reset")
    public Map<String, Object> reset(@PathVariable("feedId") String feedId, @RequestParam("to") String to) {
        LOG.info("demo: reset feed '{}' to {}", feedId, to);
        FeedRuntime feed = feeds.get(feedId);
        FeedPointer pointer = switch (to) {
            case "oldest" -> feed.resetToOldest();
            case "now" -> feed.resetFromNow();
            default -> throw new IllegalArgumentException("to must be 'oldest' or 'now', was '" + to + "'");
        };
        Map<String, Object> status = status(feed);
        status.put("feedPointer", pointer.toString());
        return status;
    }

    /**
     * Processes a raw content item (JSON body, e.g. {@code {"aField": "recovered-042"}}) as if it had been on the
     * feed — the {@code EntryPusher} building block (decodes + invokes the handler within the transaction).
     */
    @PostMapping("/{feedId}/push")
    public void push(@PathVariable("feedId") String feedId, @RequestBody String rawContent) {
        LOG.info("demo: push on feed '{}': {}", feedId, rawContent);
        feeds.get(feedId).pusher().pushEntry(rawContent);
    }

    /** The run in flight did not stop within the feed's stop timeout → 409 with the message; nothing changed. */
    @ExceptionHandler(FeedStillRunningException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> onFeedStillRunning(FeedStillRunningException e) {
        return Map.of("message", e.getMessage());
    }

    private static Map<String, Object> status(FeedRuntime feed) {
        FeedRunner runner = feed.runner();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("feedId", feed.feedId());
        status.put("active", runner.isActive());
        status.put("running", runner.isRunning());
        status.put("consecutiveFailures", runner.consecutiveFailures());
        status.put("nextRun", runner.nextRun());
        return status;
    }
}
