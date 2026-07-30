package be.wegenenverkeer.atomium.client.core.demo;

import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.FeedRuntime;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Control of the handler feeds during the demo: view status, activate/deactivate (the {@link FeedRunner}),
 * and push a standalone content item (the {@code EntryPusher}). This is what the Spring Boot module's admin
 * endpoint offers out of the box; a core user writes it in this shape themselves — the building blocks expose
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

    /** The lifecycle and backoff status of every feed. */
    @GetMapping
    public List<Map<String, Object>> status() {
        return feeds.all().stream().map(DemoControlEndpoint::status).toList();
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
     * Processes a raw content item (JSON body, e.g. {@code {"aField": "recovered-042"}}) as if it had been on the
     * feed — the {@code EntryPusher} building block (decodes + invokes the handler within the transaction).
     */
    @PostMapping("/{feedId}/push")
    public void push(@PathVariable("feedId") String feedId, @RequestBody String rawContent) {
        LOG.info("demo: push on feed '{}': {}", feedId, rawContent);
        feeds.get(feedId).pusher().pushEntry(rawContent);
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
