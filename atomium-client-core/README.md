# atomium-client-core

> Part of the [`atomium-client` suite](../README.md) — see there for the overview and the integration paths
> (pure Java, Spring, Spring Boot). This module is the pure-Java core.

Framework-independent Java library for consuming an Atomium feed, with two APIs:

- **the fetch API** (`be.wegenenverkeer.atomium.client.fetch`) — low-level: a stateless client with which
  you fetch pages yourself and manage the read position;
- **the handler API** (`be.wegenenverkeer.atomium.client.handler`) — high-level: you implement a
  `FeedHandler`, the framework walks the feed and takes care of batches, transactions, pointer
  persistence, backoff and observability events.

The handler API builds on the fetch API; the shared vocabulary (`protocol`, the ports in `port`, the
exceptions in `exception`) sits alongside them as siblings.

## The Atomium protocol in a nutshell

An Atomium feed is a **paginated** stream of immutable events.
Each page contains `links` for navigation and `entries`.
The client typically starts at the *head* (the feed base URL without a page href),
finds the link to the oldest page there and then navigates from page to page.

- The **youngest** page is incomplete: it can still receive entries until it is full, after which it gets
  a link to the younger page and never changes again (complete and immutable).
- Forward consumption goes from the oldest to the youngest page, via the `previous` link; at the head the
  client keeps polling until a younger page appears.
- Within a page the entries appear *youngest-first* in the JSON; the client delivers them *oldest-first*.
- The `rel` names of the links are confusing (the oldest page carries `last`, `next` points to the *older*
  page and `previous` to the *younger* one). To avoid confusion the API consistently works with the
  terminology "oldest/youngest/older/younger". The translation of these four `rel` names lives exclusively
  in `FeedPageRel`.

## The fetch API

The `AtomiumClient` is **stateless**; the read position lives entirely in a
`FeedPointer`, a value object the application keeps itself
(typically in a database, stored within the database transaction that processes the feed entry).
This also allows the position to be moved at runtime (e.g. via an admin endpoint), and keeps
the library independent of how an application persists, schedules or retries.

The content of an entry remains a **raw JSON String**: the library does not deserialize it, so the
consumer can deserialize per entry and catch a deserialization error: log it, possibly dead-letter queue it, and decide whether or not to process subsequent feed events.

An `AtomiumClient` is created per feed: the feed's base URL is implicit in the feedHttpClient.

### Two adapters supplied by the application

- **`FeedHttpClient`** — performs the HTTP GET (with logging, retries, auth, base URL, …).
- **`FeedPageDecoder`** — parses the JSON envelope into a `FeedPage` (and keeps `content.value` raw).

### Typical usage

`fetch` returns an empty `Optional` when the source answers `304 Not Modified` (based on the etag in the
pointer): there is nothing new and the pointer simply stays put.

```java
var client = new AtomiumClient(feedHttpClient, feedPageDecoder);

FeedPointer pointer = readFeedPointerFromStorage();   // or client.pointerToOldest() / client.pointerFromNow() / new FeedPointer("/182")
while (true) {
    FetchResult result = client.fetch(pointer).orElse(null);
    if (result == null) {
        break;  // 304 Not Modified
    }
    for (FetchEntry fetchEntry : result.fetchEntries()) {
        doInTransaction(() -> {
            handle(fetchEntry.entry());
            persistFeedPointer(fetchEntry.nextFeedPointer());
        });
    }
    pointer = result.nextFeedPointer();
    doInTransaction(() -> persistFeedPointer(result.nextFeedPointer()));
    if (!result.feedHasMorePages()) {
        break;   // head processed; 'pointer' (with etag) is the starting point for polling again later
    }
}
```

You can find more examples in `be.wegenenverkeer.atomium.client.fetch.AtomiumClientTest.Scenarios`.

## The handler API

If you prefer to have the feed processed declaratively, you implement only a **`FeedHandler`**:

- **`EntryFeedHandler`** — process the entries one by one (the common case);
- **`SimpleBatchedProcessingFeedHandler`** — process them per batch, in two phases: `process` prepares the
  batch outside the transaction (collect, dedupe, look things up remotely), `persist` writes the prepared
  effect inside the transaction that also advances the feed pointer. For feeds that deliver bursts, and for
  processing that looks entries up in bulk against a remote API.

You bundle the handler with the building blocks into a **`Feed`** (via the builder) and let
**`FeedRuntime`** assemble the machinery from it: walking the feed up to the head, buffering and batching,
short transactions in which the handler effect and the `FeedPointer` commit together, backoff on failed
runs, and `FeedEventListener` events for logging/metrics/alerting.

What do you supply yourself? A `FeedHttpClient` (or [`atomium-client-restclient`](../atomium-client-restclient/README.md)),
the two decoders (or [`atomium-client-jackson-3`](../atomium-client-jackson-3/README.md)), a
`FeedPointerRepository` and `FeedTransactions` on your own persistence, and optionally your own executor
or scheduler. The rest is framework.

```java
var atomiumClient = new AtomiumClient(myFeedHttpClient, new JacksonFeedPageDecoder());
var feed = Feed.builder("myfeed", new MyFeedHandler(), atomiumClient, contentDecoder)
        .pointerRepository(myPointerRepository)       // default: in-memory (non-persistent)
        .transactions(myTransactions)                 // default: without transactions
        .initialFeedPointer(atomiumClient::pointerToOldest)
        .queryInterval(Duration.ofSeconds(30))
        .activeOnStartup(true)
        .build();

var feeds = new Feeds(List.of(FeedRuntime.of(feed)));
var scheduler = new SimpleFeedScheduler(feeds);
scheduler.start();   // ticks every second; each feed polls at its own queryInterval
// ... on application shutdown:
scheduler.close();
feeds.close();       // in-flight runs stop cleanly after their next commit point
```

The shutdown order in the example is deliberate: first `scheduler.close()` (no more ticks), then
`feeds.close()` (in-flight runs coast to a stop at their next commit point). If you already have your own
scheduler, leave out the `SimpleFeedScheduler` and call `runtime.runner().tryToStart()` yourself
frequently (e.g. every second) (non-blocking; the runner itself guards the queryInterval and the backoff,
and the run executes on the feed's executor). A complete, running example of precisely
this assembly — both minimal and with all building blocks — is
[`atomium-client-core-demo`](../atomium-client-core-demo/README.md).

With [`atomium-client-jackson-3`](../atomium-client-jackson-3/README.md) you don't have to write the
`contentDecoder` yourself: `JacksonFeedContentDecoder.of(handler, jsonMapper)` derives the content type
from the type of your handler. The builder defaults (in-memory pointers, no transactions) are meant for
tests and demos; in production you supply a `FeedPointerRepository` and `FeedTransactions` on your own
persistence. In a
Spring Boot application you don't have to write any of this yourself:
[`atomium-client-spring-boot-4`](../atomium-client-spring-boot-4/README.md) assembles the `Feed` fully
auto-configured (RestClient, JDBC pointer persistence, transactions, scheduling, admin endpoint).

## For maintainers

- **Minimal dependencies.** The main API depends only on `org.jspecify` (nullness annotations) and
  `org.slf4j:slf4j-api` (logging from the handler API). No Jackson, Spring or HTTP client. HTTP and
  JSON parsing come in via the two ports, supplied by the application (or a thin adapter library).

### Test conventions

Keep new tests in the same style, so they stay readable and consistent:

- Small, focused tests with a clear name, grouped with `@Nested`; AssertJ; static
  test-data helpers at the bottom.
- Simple, readable test data: pages of 3 entries, non-random ids (`id-001`, …), content with a single
  field (`{"aField": "fieldValue-1"}`), readable timestamps (`2026-01-31T12:01:00+01:00`; seconds/millis
  only in a focused timestamp test). Relative links **without pagesize** (`/0`, `/1`, `/2`).
- Focused tests build their JSON inline via `FeedFixture` (or a text block). The **scenario tests**
  at the top use explicit JSON files from `src/test/resources/feedpages/` — these also serve as a
  concrete illustration of the protocol.
- `FakeFeedHttpClient` simulates a server with or without etag support (test both cases);
  `JacksonFeedPageDecoder` is the reference mapper that keeps `content.value` raw.
- The functional spec of the handler API is `FeedConsumerTest`: the real fetch API on top of a
  `FakeFeedHttpClient`, synchronous runs via an inline executor, and recording helpers for handler
  callbacks, events and transactions. New consumer scenarios belong there, in the appropriate `@Nested` theme.
