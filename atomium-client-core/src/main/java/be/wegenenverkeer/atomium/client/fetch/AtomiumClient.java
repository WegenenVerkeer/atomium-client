package be.wegenenverkeer.atomium.client.fetch;

import be.wegenenverkeer.atomium.client.exception.AtomiumClientException;
import be.wegenenverkeer.atomium.client.exception.AtomiumHttpException;
import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;
import be.wegenenverkeer.atomium.client.exception.AtomiumPageGoneException;
import be.wegenenverkeer.atomium.client.port.FeedHttpClient;
import be.wegenenverkeer.atomium.client.port.FeedPageDecoder;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPage;
import be.wegenenverkeer.atomium.client.protocol.FeedPageRel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static be.wegenenverkeer.atomium.client.protocol.FeedPageRel.SELF;

/**
 * A synchronous, stateless client to consume an Atomium feed.
 *
 * <p>One {@code AtomiumClient} is used per feed: the supplied {@link FeedHttpClient} is bound to
 * one feed base URL.</p>
 *
 * <p>The client keeps no state of its own — the complete read position lives in the
 * {@link FeedPointer}s that are returned and kept by the consumer.</p>
 *
 * <p>See the README of {@code atomium-client-core} for a complete usage example.
 */
public final class AtomiumClient {

    /**
     * The relative link denoting the head (youngest page) of the feed: the base URL without a page href.
     */
    private static final String HEAD_LINK = "";

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_MODIFIED = 304;
    private static final int HTTP_GONE = 410;

    private final FeedHttpClient httpClient;
    private final FeedPageDecoder feedPageDecoder;

    /**
     * @param httpClient      the port used to send HTTP requests
     * @param feedPageDecoder the port used to decode the JSON envelope of a feed page
     */
    public AtomiumClient(FeedHttpClient httpClient, FeedPageDecoder feedPageDecoder) {
        this.httpClient = httpClient;
        this.feedPageDecoder = feedPageDecoder;
    }

    /**
     * Fetch a page of entries starting from the given pointer.
     *
     * <p>The fetchResult contains fetchEntries that are new since the feedPointer,
     * and a nextFeedPointer for the next fetch.</p>
     *
     * <p>If the {@link FetchCoordinate#etag()} is set, and the server did not modify the page,
     * the server may (optionally, not all servers support this) reply with a {@code 304 Not Modified},
     * in which case the result is {@link Optional#empty()}
     * (the pointer remains valid to poll with again later).</p>
     *
     * <p>If the {@link FetchCoordinate#filterEventId()} is set, all entries up to and including this event are filtered out of the result (they were already delivered).</p>
     *
     * @return the fetch result, or {@link Optional#empty()} on a {@code 304 Not Modified}
     * @throws AtomiumPageGoneException      on a {@code 410 Gone} (purged page)
     * @throws AtomiumHttpException          on any other unexpected HTTP status
     * @throws AtomiumInvalidPageException on an invalid page envelope
     */
    public Optional<FetchResult> fetch(FeedPointer feedPointer) {
        FetchCoordinate fetch = feedPointer.nextFetch();
        FeedHttpClient.HttpResponse response = httpClient.get(fetch.pageLink(), fetch.etag());
        if (response.status() == HTTP_NOT_MODIFIED) {
            return Optional.empty();
        }
        FeedPage feedPage = parseOrThrow(response, fetch.pageLink());
        return Optional.of(buildFetchResult(feedPage, response.etag(), feedPointer.lastEvent(), fetch.filterEventId()));
    }

    /**
     * Fetch a page <em>raw</em> (for diagnostics/troubleshooting): performs {@link FeedHttpClient#get} and
     * returns the {@link FeedHttpClient.HttpResponse} unchanged (status, headers/etag, body). Nothing is
     * decoded, filtered or compared against an etag.
     *
     * @param pageLink the page href relative to the base URL (e.g. {@code "/182"}); an empty string is the head
     */
    public FeedHttpClient.HttpResponse fetchRawPage(String pageLink) {
        return httpClient.get(pageLink, null);
    }

    /**
     * Fetch the youngest page (the head) with all its current events.
     *
     * <p>Use this when you want to process the currently available entries of the youngest page. If,
     * on the other hand, you only want <em>future</em> events (skipping the existing ones), use
     * {@link #pointerFromNow()}.
     */
    public FetchResult fetchYoungest() {
        FeedHttpClient.HttpResponse response = httpClient.get(HEAD_LINK, null);
        FeedPage head = parseOrThrow(response, HEAD_LINK);
        return buildFetchResult(head, response.etag(), null, null);
    }

    /**
     * A pointer to the oldest page, to consume the complete feed from the beginning.
     */
    public FeedPointer pointerToOldest() {
        FeedPage head = fetchHeadPage();
        return new FeedPointer(head.metadata().href(FeedPageRel.OLDEST));
    }

    /**
     * A pointer "from now": the existing events on the youngest page are skipped, so that a
     * next {@link #fetch(FeedPointer)} only delivers events added after this moment.
     */
    public FeedPointer pointerFromNow() {
        FeedHttpClient.HttpResponse response = httpClient.get(HEAD_LINK, null);
        FeedPage head = parseOrThrow(response, HEAD_LINK);
        FetchCoordinate nextFetch = nextFetchCoordinate(head, response.etag());
        // 'from now' = synchronized up to and including the youngest existing event; that is the anchor to resume after
        List<AtomiumEntry> entries = head.entries();
        EventCoordinate lastEvent = entries.isEmpty()
                ? null
                : new EventCoordinate(head.metadata().href(SELF), entries.get(0).id());
        return new FeedPointer(lastEvent, nextFetch);
    }

    private FeedPage fetchHeadPage() {
        FeedHttpClient.HttpResponse response = httpClient.get(HEAD_LINK, null);
        return parseOrThrow(response, HEAD_LINK);
    }

    private FeedPage parseOrThrow(FeedHttpClient.HttpResponse response, String relativeLink) {
        int status = response.status();
        if (status == HTTP_GONE) {
            throw new AtomiumPageGoneException(relativeLink);
        }
        if (status != HTTP_OK) {
            throw new AtomiumHttpException(status, relativeLink);
        }
        String body = response.body();
        if (body == null) {
            throw new AtomiumInvalidPageException("empty response body while fetching '%s'".formatted(relativeLink));
        }
        try {
            return feedPageDecoder.readFeedPage(body);
        } catch (AtomiumClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AtomiumInvalidPageException("could not parse feed page '%s'".formatted(relativeLink), e);
        }
    }

    /**
     * Build the fetch result: reverse the entries to oldest-first, filter out the events already delivered, and
     * calculate the pointers (per page and per entry). The {@code lastEvent} of the result pointer is the
     * youngest just-delivered entry, or — if there was nothing new — the incoming {@code lastEvent} carried
     * along unchanged.
     */
    private FetchResult buildFetchResult(FeedPage feedPage, @Nullable String etag,
                                            @Nullable EventCoordinate incomingLastEvent, @Nullable String filterEventId) {
        String self = feedPage.metadata().href(SELF);
        List<AtomiumEntry> oldestFirst = reversed(feedPage.entries());
        List<AtomiumEntry> newEntries = filterAlreadyDelivered(oldestFirst, filterEventId);

        FetchCoordinate nextFetch = nextFetchCoordinate(feedPage, etag);
        EventCoordinate lastEventAfter = newEntries.isEmpty()
                ? incomingLastEvent
                : new EventCoordinate(self, newEntries.get(newEntries.size() - 1).id());
        FeedPointer resultPointer = new FeedPointer(lastEventAfter, nextFetch);

        List<FetchEntry> fetchEntries = fetchEntries(self, newEntries, resultPointer);

        boolean feedHasMorePages = feedPage.metadata().optionalHref(FeedPageRel.YOUNGER).isPresent();
        return new FetchResult(fetchEntries, resultPointer, feedPage.metadata(), feedHasMorePages);
    }

    /**
     * Reverse the entries: the JSON delivers youngest-first, the consumer wants oldest-first.
     */
    private static List<AtomiumEntry> reversed(List<AtomiumEntry> entries) {
        List<AtomiumEntry> result = new ArrayList<>(entries);
        Collections.reverse(result);
        return result;
    }

    /**
     * Filter out all entries up to and including {@code lastEventId} (they were already delivered). If {@code
     * lastEventId} does not occur on the page, nothing is filtered.
     */
    private static List<AtomiumEntry> filterAlreadyDelivered(List<AtomiumEntry> oldestFirst, @Nullable String lastEventId) {
        if (lastEventId == null) {
            return oldestFirst;
        }
        for (int i = 0; i < oldestFirst.size(); i++) {
            if (oldestFirst.get(i).id().equals(lastEventId)) {
                return new ArrayList<>(oldestFirst.subList(i + 1, oldestFirst.size()));
            }
        }
        return oldestFirst;
    }

    /**
     * The page-level fetch instruction for the next attempt.
     * <ul>
     *   <li>complete page (has a younger page) → go to the younger page (no filter, no etag);</li>
     *   <li>incomplete page → keep polling this page and dedup from the youngest entry
     *       ({@code self}, with etag). An incomplete page can be empty (e.g. with a "filtered" feed).</li>
     * </ul>
     */
    private static FetchCoordinate nextFetchCoordinate(FeedPage feedPage, @Nullable String etag) {
        var younger = feedPage.metadata().optionalHref(FeedPageRel.YOUNGER).orElse(null);
        if (younger == null) {
            // Keep polling this page, and skip already read entries (if there are entries at all).
            // Note that the page may well be empty (especially in the case of a "filtered" feed).
            String self = feedPage.metadata().href(SELF);
            List<AtomiumEntry> entries = feedPage.entries();
            var filterEventId = entries.isEmpty() ? null : entries.get(0).id();
            return new FetchCoordinate(self, filterEventId, etag);
        } else {
            return new FetchCoordinate(younger, null, null);
        }
    }

    /**
     * Calculate, for each delivered entry, the {@link FeedPointer} to continue reading <em>after</em> that entry:
     * {@code lastEvent} = (this page, entry id), and the fetch continues on the same page with that id as filter —
     * deliberately <em>without etag</em>, so that resuming halfway through the page forces a 200 and the remaining
     * entries still get delivered (an etag could yield a 304 and hide the rest of the page). The last entry
     * gets the etag-safe result pointer.
     */
    private static List<FetchEntry> fetchEntries(String self, List<AtomiumEntry> newEntries, FeedPointer resultPointer) {
        List<FetchEntry> result = new ArrayList<>(newEntries.size());
        for (int i = 0; i < newEntries.size(); i++) {
            AtomiumEntry entry = newEntries.get(i);
            boolean last = i == newEntries.size() - 1;
            FeedPointer pointer = last
                    ? resultPointer
                    : new FeedPointer(new EventCoordinate(self, entry.id()), new FetchCoordinate(self, entry.id(), null));
            result.add(new FetchEntry(entry, pointer));
        }
        return result;
    }
}
