package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Management/diagnostics endpoint for the atomium feeds. Delegates everything to the {@link AtomiumAdminService}.
 * Only registered (via the auto-configuration) in a web application and when
 * {@code atomium.admin.enabled=true}.
 *
 * <p><b>Security:</b> every operation starts with a check on the application's (required)
 * {@link AtomiumAdminAuthorization} bean: the read-only diagnostics (GET) via {@code assertReadPermission()},
 * the mutating operations (PUT/POST) via {@code assertWritePermission()}.
 *
 * <p>If {@code atomium.admin.pretty-print=true}, the JSON output is indented; otherwise Spring serializes
 * the objects the usual way.
 */
@RestController
@RequestMapping("/rest/atomium")
public class AtomiumAdminEndpoint {

    // every mutating call (PUT/POST) is logged at INFO
    private static final Logger LOG = LoggerFactory.getLogger(AtomiumAdminEndpoint.class);

    private final AtomiumAdminService atomiumAdminService;
    private final AtomiumAdminAuthorization authorization;
    private final JsonMapper jsonMapper;              // to parse raw (JSON) bodies
    // non-null => return indented JSON (pretty print)
    private final @Nullable JsonMapper prettyMapper;

    public AtomiumAdminEndpoint(AtomiumAdminService atomiumAdminService, AtomiumAdminAuthorization authorization,
                                JsonMapper jsonMapper, boolean prettyPrint) {
        this.atomiumAdminService = atomiumAdminService;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
        this.prettyMapper = prettyPrint
                ? jsonMapper.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build()
                : null;
    }

    /** The persisted feed pointer of every feed. */
    @GetMapping("/feed-pointer")
    public ResponseEntity<?> feedPointers() {
        authorization.assertReadPermission();
        return json(atomiumAdminService.feedPointers());
    }

    /** The persisted feed pointer of one feed. */
    @GetMapping("/feed/{feedId}/feed-pointer")
    public ResponseEntity<?> feedPointer(@PathVariable String feedId) {
        authorization.assertReadPermission();
        return json(atomiumAdminService.feedPointer(feedId));
    }

    /** The lifecycle status (active/running) of every feed. */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        authorization.assertReadPermission();
        return json(atomiumAdminService.statuses());
    }

    /** The lifecycle status (active/running) of one feed. */
    @GetMapping("/feed/{feedId}/status")
    public ResponseEntity<?> feedStatus(@PathVariable String feedId) {
        authorization.assertReadPermission();
        return json(atomiumAdminService.status(feedId));
    }

    /** The resolved config of every feed. */
    @GetMapping("/config")
    public ResponseEntity<?> config() {
        authorization.assertReadPermission();
        return json(atomiumAdminService.config());
    }

    /** The resolved config of one feed. */
    @GetMapping("/feed/{feedId}/config")
    public ResponseEntity<?> feedConfig(@PathVariable String feedId) {
        authorization.assertReadPermission();
        return json(atomiumAdminService.config(feedId));
    }

    /**
     * Move the feed pointer of a feed (only when it is inactive and no run is in progress). The request body gives the
     * {@code pageLink} and optionally the last processed {@code eventId} ({@link SetFeedPointerCommand}); with an
     * {@code eventId} the next run resumes just after that event, otherwise from the start of the page.
     */
    @PutMapping("/feed/{feedId}/feed-pointer")
    public void setFeedPointer(@PathVariable String feedId, @RequestBody SetFeedPointerCommand command) {
        authorization.assertWritePermission();
        LOG.info("feed '{}': moving feed-pointer to pageLink '{}' (eventId {})",
                feedId, command.pageLink(), command.eventId());
        atomiumAdminService.setFeedPointer(feedId, command.toFeedPointer());
    }

    /** Activate a feed. */
    @PutMapping("/feed/{feedId}/activate")
    public void activate(@PathVariable String feedId) {
        authorization.assertWritePermission();
        LOG.info("feed '{}': activating via admin endpoint", feedId);
        atomiumAdminService.activate(feedId);
    }

    /** Deactivate a feed. */
    @PutMapping("/feed/{feedId}/deactivate")
    public void deactivate(@PathVariable String feedId) {
        authorization.assertWritePermission();
        LOG.info("feed '{}': deactivating via admin endpoint", feedId);
        atomiumAdminService.deactivate(feedId);
    }

    /**
     * Process a content item on a feed as if it appeared as an entry on the feed (troubleshooting/repair).
     * The request body is the raw (JSON) content of the item.
     */
    @PostMapping("/feed/{feedId}/push")
    public void pushEntry(@PathVariable String feedId, @RequestBody String content) {
        authorization.assertWritePermission();
        // we deliberately do not log the content itself (may be large/sensitive) — only THAT a push happened
        LOG.info("feed '{}': pushing entry via admin endpoint", feedId);
        atomiumAdminService.pushEntry(feedId, content);
    }

    /** The raw head page of a feed (diagnostics). */
    @GetMapping("/feed/{feedId}/feed")
    public ResponseEntity<?> feedHead(@PathVariable String feedId) {
        authorization.assertReadPermission();
        return json(rawPage(atomiumAdminService.rawPage(feedId, "")));
    }

    /**
     * A raw page of a feed (diagnostics), e.g. {@code /feed/{feedId}/feed/182} or {@code …/feed/0/100}.
     * {@code {*pageLink}} is a capture-the-rest: it matches the entire remainder of the path, including slashes, and
     * contains the leading {@code /}. That is exactly the page href ({@code pageLink}), so no prefix needed.
     */
    @GetMapping("/feed/{feedId}/feed/{*pageLink}")
    public ResponseEntity<?> feedPage(@PathVariable String feedId, @PathVariable String pageLink) {
        authorization.assertReadPermission();
        return json(rawPage(atomiumAdminService.rawPage(feedId, pageLink)));
    }

    /** An unknown feed → 404 with the message. */
    @ExceptionHandler(AtomiumUnknownFeedException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> onUnknownFeed(AtomiumUnknownFeedException e) {
        return Map.of("message", e.getMessage());
    }

    /** A validation error (disallowed state of a known feed) → 400 with the message. */
    @ExceptionHandler(AtomiumAdminValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onValidationError(AtomiumAdminValidationException e) {
        return Map.of("message", e.getMessage());
    }

    /** Wrap the raw HTTP response in a DTO; a JSON body is shown as nested JSON, not as a string. */
    private RawPageDto rawPage(FeedHttpClient.HttpResponse response) {
        return new RawPageDto(response.status(), response.headers(), bodyAsJsonIfPossible(response.body()));
    }

    private @Nullable Object bodyAsJsonIfPossible(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            return jsonMapper.readTree(body); // valid JSON → nested JSON in the output
        } catch (RuntimeException e) {
            return body;                      // no (valid) JSON → leave it a string
        }
    }

    /** Serialize ourselves (indented) when pretty print is on; otherwise let Spring serialize the body. */
    private ResponseEntity<?> json(Object body) {
        if (prettyMapper == null) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(prettyMapper.writeValueAsString(body));
    }
}
