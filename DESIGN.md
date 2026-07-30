# Atomium-client — design & glossary

> A **map**, not full documentation: the most important classes per module, in a logical order, one line
> each. Meant to get your bearings quickly and to look up "what was X again, and how does it differ from Y".
> The detail lives in the javadoc and the code; the why's in [`AGENTS.md`](AGENTS.md).

---

## atomium-client-core

Framework-independent Java library for consuming an Atomium feed: a low-level **fetch API**
(`client.fetch`) and a high-level **handler API** (`client.handler`), with the shared vocabulary in
`client.protocol` / `client.port` / `client.exception`.

### Protocol — the decoded feed model (`client.protocol`)

- **FeedPage** — one fetched feed page: `FeedPageMetadata` + a list of `AtomiumEntry`s.
- **AtomiumEntry** — one event on the feed: id, `updated` timestamp, `Content`, links.
- **Content** — the content of an entry: a type + the raw JSON `value`.
- **FeedPageMetadata** — the metadata of a page, including the `Link`s to other pages.
- **Link** / **FeedPageRel** — a link (rel + href) to another page; `FeedPageRel` is the set of relations (self / last / previous / next).
- **FeedHttpClient** (`client.port`) — port for the HTTP GETs (impl: `SpringFeedHttpClient` in `-restclient`).
- **FeedPageDecoder** (`client.port`) — port that decodes the JSON envelope into a `FeedPage` (impl: `JacksonFeedPageDecoder` in `-jackson-3`).

### The fetch API — reading with a movable read position (`client.fetch`)

- **AtomiumClient** — synchronous, stateless client; `fetch(FeedPointer)` → `FetchResult`.
- **FeedPointer** — the complete read position: an `EventCoordinate` (last processed) + a `FetchCoordinate` (next fetch).
- **EventCoordinate** — where one event lives: pageLink + eventId.
- **FetchCoordinate** — what `fetch` needs to continue reading efficiently: pageLink + filterEventId + etag.
- **FetchResult** — the result of a fetch: the `FetchEntry`s, the next `FeedPointer`, the `FeedPageMetadata`.
- **FetchEntry** — one fetched `AtomiumEntry` + the `FeedPointer` to continue reading right after this entry.

### The handler API — what the developer implements (SPI) (`client.handler`)

- **FeedHandler** — the base: only `getFeedId()` (+ opt-in `accepts()` to filter, `pushEntry()` for a push). Implement one of the two variants:
- **EntryFeedHandler** — processes the entries **one by one** (`onEntry`); the typical case.
- **BatchedFeedHandler** — processes them **per (deduplicated) batch** (`onBatch`); for feeds that deliver bursts faster than you can handle per entry.
- **FeedHandlerBatch** — the accumulator of one batch; decides for itself via `isComplete()` when it is done (the extension point).
- **DefaultFeedHandlerBatch** — ready-made batch: dedup on a key (last-wins), complete on the number of distinct keys.
- **BatchEntry** — one buffered entry: `FeedPageMetadata` + `AtomiumEntry` + decoded content.
- **FeedEventListener** — observability SPI: the single point where the processing reports its events (payloads: `FeedRunResult`, `FeedRunFailure`).

### The handler API — definition & assembly

- **Feed** — the definition of one feed: the handler, the building blocks (`AtomiumClient`, `FeedContentDecoder`, `FeedPointerRepository`, `FeedTransactions`) and the configuration; built via a builder with defaults.
- **FeedContentDecoder** — decodes the raw entry content into the handler's domain type (impl: `JacksonFeedContentDecoder` in `-jackson-3`, which derives the content type from the handler type).
- **FeedTransactions** — port for the transaction within which the handler effect and the feed pointer commit together (`withoutTransactions()` for those who have no tx).
- **FeedPointerRepository** — port for the pointer persistence; **InMemoryFeedPointerRepository** is the non-persistent builder default (tests/demos).
- **FeedRuntime** — assembles the running machinery from a `Feed` (runner + consumer + pusher); the per-feed entry point for scheduler and management tooling.
- **Feeds** — the registry of all `FeedRuntime`s (lookup by feedId).
- **EntryPusher** — process a loose content item as if it were on the feed (troubleshooting/repair).
- **LoggingFeedEventListener** — the bundled `FeedEventListener` (logs the events).

### The handler API — the run pipeline (framework, internal)

> scheduler → `FeedRunner` → `FeedConsumer` → `FeedHandlerController` → your `FeedHandler`

- **FeedRunner** — the lifecycle of one feed: active/inactive, start/interrupt, the timing of the next run (`queryInterval` on success, backoff after failures), emits `runFailed`; triggered frequently by a scheduler.
- **SimpleFeedScheduler** — bundled minimal scheduler (own daemon thread): one frequent tick (default every second) to `tryToStart()` of every feed; for apps without a framework scheduler (Spring Boot apps get the `FeedScheduler` from `-spring-boot-4`).
- **FeedConsumer** / **FeedConsumerImpl** — reads the feed from the `FeedPointer` up to the head; manages the transactions + pointer commits and emits all other events.
- **FeedHandlerController** — owns the buffer/batch, applies `accepts`, and tells the consumer via a `Code` when flushing and committing is allowed.
- **BatchedFeedHandlerController** — the only controller impl; an `EntryFeedHandler` also runs through here via the **EntryFeedHandlerAdapter** (a batch of 1).
- **FeedBackoffPolicy** / **ExponentialFeedBackoffPolicy** — how long the runner waits after consecutive failures.
- **PerFeedThreadExecutors** — provides each feed its own daemon-thread executor (with clean shutdown).

---

## atomium-client-spring-boot-4

Spring Boot layer that assembles a `Feed` per `FeedHandler` bean (RestClient, Jackson content decoder, JDBC
pointer persistence, transactions) and wraps the scheduling, metrics and the admin endpoint around it. The
integrator in principle only writes a `FeedHandler` and a `FeedRestClientBuilders`.

### Assembly & configuration

- **FeedRestClientBuilders** — the only **mandatory** seam: provide a `RestClient.Builder` per feed (base URL, auth, logging, retries).
- **FeedCustomizer** — adjust the `FeedConfiguration` per feed (add mapper / executor / backoff / listeners).
- **FeedConfiguration** — the mutable per-feed configuration object that customizers see (RestClient builder, mapper, executor, backoff, listeners).
- **FeedFactory** — builds a ready-to-start `FeedRuntime` per `FeedHandler`: defaults → customize → validate → assemble a `Feed` and hand it to `FeedRuntime.of`.
- **FeedScheduler** — frequently (default every second) asks every `FeedRunner` to start a run; the runner itself guards `query-interval` and backoff.
- **AtomiumProperties** / **AtomiumFeedProperties** — the bound config (`atomium.*` / per feed under `atomium.feeds.<id>`).
- **AtomiumFeedAutoConfiguration** — the Spring Boot auto-config: discovers the `FeedHandler` beans and sets up all of the above.

### Persistence & observability

- **JdbcFeedPointerRepository** — the default `FeedPointerRepository`: JDBC, table `atomium_feed_pointer_v1`.
- **MicrometerFeedEventListener** / **AtomiumMetricsAutoConfiguration** — metrics as a `FeedEventListener` consumer (optional Micrometer dependency).
- **admin/** — the `/rest/atomium/**` endpoint to inspect and control feeds (own subpackage); authorization via the mandatory **AtomiumAdminAuthorization** bean (ready-made: **HasAuthorityAtomiumAdminAuthorization**).

---

## atomium-client-core-demo

Standalone app that demonstrates the core APIs without the Boot module, against its own in-memory source
feed (`DemoFeedEndpoint`); Spring Boot is only application setup there.

- **FetchDemoEndpoint** — the fetch API in its pure form: reads the complete feed from oldest page to head on every request.
- **SimpleFeedHandler** / **SimpleDemoConfiguration** — the minimal handler assembly: the builder parameters, a start position and the poll settings; the building blocks are the defaults.
- **SimpleBatchedFeedHandler** / **SimpleBatchedDemoConfiguration** — the batched variant: a `BatchedFeedHandler` with the bundled `DefaultFeedHandlerBatch`.
- **FullMontyFeedHandler** / **FullMontyDemoConfiguration** — the full assembly: every building block explicit, with a real JDBC `FeedPointerRepository` (**DemoJdbcFeedPointerRepository**, H2) and real `FeedTransactions` (**SpringTransactionFeedTransactions**), plus a custom content DTO (`MontyContent`).
- **DemoControlEndpoint** — status / activate / deactivate / push (`/rest/demo/feeds`), on top of the `Feeds` registry and the `SimpleFeedScheduler`.

---

## atomium-client-spring-boot-4-demo

Standalone Spring Boot app that demonstrates the lib against an in-memory source feed (`DemoFeedEndpoint`).

- **SimpleFeedHandler** — the minimal integration: an `EntryFeedHandler` on a raw `JsonNode`.
- **SimpleBatchedFeedHandler** — the batched variant: a `BatchedFeedHandler` with the bundled `DefaultFeedHandlerBatch`.
- **FullMontyFeedHandler** — shows the extras: an `EntryFeedHandler` with a custom content DTO (`MontyContent`).
