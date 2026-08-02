# atomium-client-spring-boot-4

> Part of the [`atomium-client` suite](../README.md) — see there for the overview, what an Atomium feed is,
> and the other integration paths (pure Java, Spring). This is the **Spring Boot** path.

The **Spring Boot integration layer** and, for most Spring Boot users, the only module you need directly.
It wires the complete consumer for you: it discovers your `FeedHandler` beans, polls each feed,
deserializes the content, persists the feed pointer (JDBC, in the same transaction as the processing),
applies backoff on failures and offers an optional admin/diagnostics endpoint. It brings in
`atomium-client-{core,jackson-3,restclient}` transitively.

You supply two things: a **`FeedHandler`** (what to do with each event) and a
**`FeedRestClientBuilders`** (how to reach the source feed). Everything else has sensible defaults.

## Getting started

1. **Add the dependency** (brings in `atomium-client-{core,jackson-3,restclient}` transitively):

   ```xml
   <dependency>
       <groupId>be.wegenenverkeer</groupId>
       <artifactId>atomium-client-spring-boot-4</artifactId>
       <version>...</version>
   </dependency>
   ```

   The auto-configuration starts by itself. It expects a `DataSource` (`JdbcClient`) and a
   `PlatformTransactionManager` in the context — in an ordinary Spring Boot app that is the default. Import the
   [`atomium-client-bom`](../README.md#versions--compatibility) to pin the suite versions consistently.
2. Implement a [`FeedHandler`](#the-feedhandlerc) bean — what to do with each event (two variants; the
   choice is explained below).
3. Provide a [`FeedRestClientBuilders`](#the-feedrestclientbuilders) bean — how to reach the source feed.
4. [Configure](#configuration-atomium) the feed under `atomium.feeds.<feedId>` — at a minimum:

   ```yaml
   atomium:
     feeds:
       a-feed-id:
         url: https://source/myfeed
         active-on-startup: true
         initial-feed-pointer:
           type: oldest
   ```
5. Provide the [DB table](#the-required-db-table) `atomium_feed_pointer_v1` (e.g. via Flyway).

The rest of this README is the reference, per component.

## The `FeedHandler<C>`

The SPI carrying your domain logic. Register it as a `@Component`; the framework puts a feed consumer on
each handler. `C` is the domain type the raw JSON content is deserialized into.

You don't implement `FeedHandler` itself, but one of its two variants. The choice hinges on **where the
processing work happens**, not on how many entities an event concerns: is the event payload self-contained
and the processing local (database writes) → `EntryFeedHandler`; does processing involve **remote** work
(the common feed shape "entity X changed, fetch its latest version") →
[`SimpleProcessingFeedHandler`](#the-simpleprocessingfeedhandlerc-p--batch-processing-in-two-phases),
even when every event concerns a single entity.

**`EntryFeedHandler<C>`** processes the events one by one:

```java
@Component
class MyEventFeedHandler implements EntryFeedHandler<MyEvent> {

    @Override
    public String getFeedId() {
        return "a-feed-id";
    }

    @Override
    public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, MyEvent content) {
        // your domain logic; the framework commits this together with the new feed pointer
    }
}
```

| Method | Required? | When |
| --- | --- | --- |
| `getFeedId()` | yes | The unique identity of the feed: at once the config key (`atomium.feeds.<feedId>`), the DB key, the thread name and the admin URL segment. Short and stable, **without dots**. |
| `onEntry(pageMetadata, entry, content)` | yes (`EntryFeedHandler`) | For each event, in read order (oldest first). Its effect is committed in one transaction together with the advanced feed pointer. |
| `process(entries)` / `persist(prepared)` | yes (`SimpleProcessingFeedHandler`) | Per batch, in two phases — see below. |
| `accepts(pageMetadata, entry, content)` | no | Is this entry relevant? `false` → the framework ignores it entirely and simply advances the pointer past it. Default: everything is relevant. |
| `pushEntry(content)` | no (opt-in, via `FeedPusher`) | Process an item **as if** it were on the feed (via the admin endpoint). Implement the separate `FeedPusher<C>` interface next to the handler variant; without it, push is unsupported. |

The handler is **pure domain** (identity + callbacks) and **stateless**: the framework owns the buffer and hands the
callback everything it needs. Infrastructure config happens elsewhere (see below).

### The `SimpleProcessingFeedHandler<C, P>` — batch processing in two phases

Some feeds produce bursts of events faster than you can process them one by one (committing per entry is then
too slow → you fall days behind). And a lot of processing involves remote work: an asset repository system
publishes change events, and you look the changed assets up against the source — in bulk if the API takes
many ids per request, otherwise call by call. Those calls do not belong inside an open database transaction:
a transaction stalled on remote I/O holds its database connection hostage exactly when the remote system is
having trouble, and an entity read before a slow call and written after it spans a needlessly wide
optimistic-locking window. The two phases make the safe shape structural rather than a convention — remote
work simply has no transaction to leak into, and as a bonus repeated ids within a burst collapse into one
lookup, which is what makes catching up a long backlog fast.

A `SimpleProcessingFeedHandler` processes the feed per batch, in **two phases**: `process` prepares the
batch **outside** any transaction (collect, dedupe, look things up remotely — may be slow, may be repeated
after a crash), `persist` writes the prepared effect **inside** the transaction that also advances the feed
pointer (fast and local — no network calls here). The framework carries the intermediate state `P` between
the two, so your bean stays stateless.

```java
@Component
class MyEventFeedHandler implements SimpleProcessingFeedHandler<MyEvent, List<Asset>> {

    @Override
    public String getFeedId() {
        return "a-feed-id";
    }

    @Override
    public ProcessResult<List<Asset>> process(List<ProcessingEntry<MyEvent>> entries) {
        Set<String> ids = entries.stream().map(e -> e.content().assetId()).collect(toSet());
        return ProcessResult.of(assetApi.lookup(ids), ids.size());   // remote call: outside the transaction
    }

    @Override
    public void persist(List<Asset> assets) {
        assets.forEach(repository::upsert);   // inside the transaction, atomic with the feed pointer
    }
}
```

Note who chooses what: **you choose what a batch does** (`process`/`persist` — that is domain and belongs in
code), **the operator chooses the size** (`processing.max-size` — tuning, and different per
environment: the maximum batch size, counted in accepted entries).

A few properties you should know:

- Entries that `accepts` rejects do not enter the batch and **do not count toward the threshold**.
- A **partial batch** is still wrapped up at the end of the feed (or on a clean interruption): a batch never
  survives a poll.
- When **reading the next entry fails** (a page fetch or a decode), the buffered batch is also wrapped up
  first — that work is in hand and processable — and only then does the run fail with the read failure. The
  next run resumes after the committed work instead of re-reading it.
- If `process` or `persist` throws, the run fails: rollback, the pointer stays put, and the whole batch is
  offered again on the next run. `process` must be able to cope with that — reads and idempotent calls are
  fine; non-idempotent side effects belong in `persist`.
- As long as a batch is not wrapped up, the feed pointer stays **pinned** — even across page boundaries. A
  crash then replays that entire batch, which is the intent.
- The framework counts `read` and `accepted` (feed entries). `processed` is yours: a free measure of the
  work the batch realised — entities upserted, documents indexed, entries left after dedup — reported via
  `ProcessResult.of(value, processed)`, and it may be smaller or larger than the number of offered entries.
  Left unreported it defaults to the number of offered entries.

#### The safety net: `processing.max-uncommitted-pages`

A feed that filters heavily (`accepts`) sometimes rarely reaches its threshold. And as long as nothing is
committed, the feed pointer doesn't advance. Without a brake that means: no intermediate progress, and
after a crash an unbounded number of pages must be fetched again — in the pathological case the run never reaches
the head and never commits.

That is why, once `max-uncommitted-pages` pages have been read without a commit (default 10), the framework asks
the processing to wrap up on every page boundary, even if the batch isn't full. Better to process half a batch
than to keep an unbounded window. The number of pages is the right measure: memory is already bounded by the
batch itself, but the *re-read cost* after a crash is not.

With an `EntryFeedHandler` (commit per entry) this safety net never triggers.

## Observability: the `FeedEventListener`

If you want to observe the processing, that is not the handler's business but a `FeedEventListener`'s. Implement
that interface (all callbacks have an empty default — take only what you need) and register it as a bean:
app-wide, or per feed via `FeedConfiguration#addListener` in a [`FeedCustomizer`](#per-feed-variation-feedcustomizer).
Metrics, health, logging and alerting all hang off this one point; the bundled `LoggingFeedEventListener` is
its simplest consumer.

| Event | When |
| --- | --- |
| `runStarted(feedId, startPosition)` | A run begins, from this pointer. |
| `pageFetched(feedId, pageMetadata, entryCount)` | A page was fetched from the source. |
| `feedNotModified(feedId)` | The source returned `304 Not Modified` — nothing new since the previous poll. |
| `feedPointerAdvanced(feedId, feedPointer, sincePreviousCommit, latestEventUpdated)` | The feed pointer was committed — the recovery point after a crash. `sincePreviousCommit` carries the counters of what was added since the previous commit; `latestEventUpdated` the `updated` of the youngest entry the commit covered (the freshness signal). |
| `pageProcessed(feedId, pageMetadata)` | A page was traversed (not necessarily committed: a batch may run across page boundaries). |
| `endOfFeedReached(feedId)` | The head was reached. |
| `runInterrupted(feedId, result)` / `runCompleted(feedId, result)` | The run stopped, cleanly interrupted or normally. |
| `runFailed(failure)` | The run failed; `FeedRunFailure` carries the backoff counter, the deadline and (where applicable) the entry context. |

**The contract:** the callbacks run on the feed thread, always **after** the commit point they belong to — never
inside an open transaction. What `feedPointerAdvanced` shows you is therefore exactly what a
crash at that moment would leave behind; work that was rolled back is never reported. A listener that throws does
not break the run (the failure is logged at WARN and ignored) — but keep implementations light and non-blocking.

`FeedRunResult` deliberately carries **three** counters: `read`, `accepted` and `processed`. On a filtering,
deduplicating feed they diverge widely (e.g. 10,000 → 800 → 120), and that difference is precisely what you want
to see. `read` and `accepted` count feed entries; `processed` is the handler's own measure of realised work
(default: entries — see `ProcessResult`). You get them twice: per commit as a delta (`feedPointerAdvanced`) and
at the end of the run as a total (`runCompleted`/`runInterrupted`).

### Metrics (Micrometer)

If your app has a `MeterRegistry` (typically via `spring-boot-starter-actuator` + a registry such as
`micrometer-registry-prometheus`), the framework publishes feed metrics **automatically** — nothing to code.
It is a bundled `FeedEventListener` (`MicrometerFeedEventListener`) that is auto-configured as soon as there is a
registry; disable it with `atomium.metrics.enabled=false`. All series are tagged with `feed`:

| Metric (prometheus name) | Type | Meaning |
| --- | --- | --- |
| `atomium_runs_total{feed,outcome}` | counter | runs, per `outcome` (`completed`/`interrupted`/`failed`) |
| `atomium_entries_read_total{feed}` / `_accepted_` / `_processed_` | counter | the three counters, summed per commit (so they advance even in the middle of a long run); `processed` carries the handler's own measure of realised work (default: entries) |
| `atomium_entries_last_commit_time_seconds{feed}` | gauge | timestamp of the last commit (is the feed still alive?) |
| `atomium_entries_last_event_time_seconds{feed}` | gauge | `updated` of the most recent processed event (how fresh is the data?) |
| `atomium_pages_fetched_total{feed}` | counter | fetched HTTP pages |
| `atomium_polls_not_modified_total{feed}` | counter | polls that got a `304 Not Modified` |
| `atomium_runs_consecutive_failures{feed}` | gauge | current number of consecutive failures (0 = healthy) |

The `spring-boot-4-demo` has this enabled: start it and look at `/management/prometheus`.

### Health

If the Spring Boot health API is on the classpath (via `spring-boot-starter-actuator`), the framework
**automatically** registers a health contributor `atomium` with one component per feed; disable it with
`atomium.health.enabled=false`:

```json
// /management/health → components.atomium.components.<feedId>
"demo": {
  "status": "UP",
  "details": { "active": true, "running": false, "consecutiveFailures": 0,
               "nextRun": "…", "lastCommit": "…", "lastEvent": "…" }
}
```

- A feed reports `DOWN` from `atomium.health.failure-threshold` (default 3) **consecutive** failed
  runs — a single failed poll is transient and resolved by the backoff itself. As soon as there is a failure (even
  below the threshold, so still at `UP`), `lastFailure` appears in the details.
- `lastCommit` (when was the last commit?) and `lastEvent` (the `updated` of the most recent processed
  event) also expose the *silent* problem feed: no failure at all, but unexpectedly nothing is being
  published or processed anymore.
- An **inactive** feed stays `UP` (with `active: false` as a detail): a deliberately deactivated feed must not
  make a pod unhealthy.
- **Use this for monitoring/alerting, not in the liveness/readiness probes.** Boot's default health groups already
  leave this indicator out of those; keep it that way (don't configure `group.liveness.include: "*"`). Restarting a
  pod doesn't fix a broken source feed, and an application with a faltering background feed can usually keep
  serving traffic just fine.

## The `FeedRestClientBuilders`

The **only mandatory seam**: supply, per feed, a `RestClient.Builder` that knows how to reach the source feed.
Without this bean the app doesn't start — that is deliberate. For the simple case it is one line:

```java
(feedId, properties) -> RestClient.builder().baseUrl(properties.url())
```

An application with shared infrastructure (TLS, auth, logging, retries, a gateway) bundles that here.
Within a single team that implementation is often identical, so a good candidate for a shared library.

## Configuration (`atomium.*`)

Per feed under `atomium.feeds.<feedId>` (map key = `getFeedId()`):

| Property | Default | Meaning |
| --- | --- | --- |
| `url` | — (required) | The feed base URL. Fails fast at startup when missing. |
| `active-on-startup` | `false` | Whether the consumer starts automatically. |
| `query-interval` | `1m` | Poll frequency: the wait between the end of a successful run and the start of the next. |
| `initial-feed-pointer.type` | — | Start position of a **new** feed: `oldest` (full history), `now` (only new events), or `pointer` (+ `page-link`). Only used as long as no pointer has been persisted yet. |
| `backoff.initial-interval` / `.max-interval` / `.multiplier` | `1m` / `1h` / `2` | Exponential backoff on consecutive failed runs. |
| `processing.max-size` | `100` | Only with a **`SimpleProcessingFeedHandler`**: the **maximum** batch size, counted in accepted entries — batches come out smaller when the safety net, the end of the feed, an interruption or a read failure wraps them up first. |
| `processing.max-uncommitted-pages` | `10` | The **safety net**: once this many pages have been read without a commit, every boundary asks the processing to wrap up, even if the batch isn't full. See [The safety net](#the-safety-net-processingmax-uncommitted-pages) above. The whole `processing.*` group is only valid on a **`SimpleProcessingFeedHandler`** feed; set on any other handler, startup fails (it commits per entry — the threshold is always 1 and the safety net can never fire). |

The HTTP client config (auth, timeouts, …) is environment-specific and doesn't belong here but in your
`FeedRestClientBuilders`; unknown sub-properties under a feed are ignored during binding.

And app-wide under `atomium.admin` and `atomium.health`:

| Property | Default | Meaning |
| --- | --- | --- |
| `admin.enabled` | `false` | Exposes the [admin endpoint](#admin-diagnostics-endpoint) (only in a web app, and only when this is explicitly `true`). |
| `admin.pretty-print` | `true` | Pretty-prints the JSON responses of the admin endpoint. |
| `health.enabled` | `true` | Registers the [health contributor](#health) (requires the health API on the classpath). |
| `health.failure-threshold` | `3` | From how many consecutive failed runs a feed reports `DOWN`. |

## Per-feed variation: `FeedCustomizer`

All defaults are already populated; if you want to adjust something per feed (an extra interceptor, a different
content mapper, a synchronous executor, your own backoff or extra event listeners), register a
`FeedCustomizer` bean. It receives the fully populated `FeedConfiguration` and mutates it:

```java
@Bean
FeedCustomizer myFeed() {
    return FeedCustomizer.forFeed("a-feed-id", feed -> {
        feed.restClientBuilder().requestInterceptor(ownInterceptor());  // add to
        feed.setContentMapper(ownMapper());                             // replace
    });
}
```

## Admin/diagnostics endpoint

With `atomium.admin.enabled=true` (and in a web app) the auto-configuration registers a `@RestController`
under **`/rest/atomium/**`**: inspect the feed pointers and lifecycle status, activate/deactivate a feed, inspect a
raw page, and (opt-in) push an item.

**Security:** as soon as the endpoint is enabled, an **`AtomiumAdminAuthorization`** bean is mandatory (the app
won't start otherwise): it decides who may perform the read-only diagnostics (GET) and the mutating operations
(PUT/POST), and throws an `AccessDeniedException` (→ 403) for anyone who may not. For the common case — one
authority for both — there is the bundled `HasAuthorityAtomiumAdminAuthorization`:

```java
@Bean
AtomiumAdminAuthorization atomiumAdminAuthorization() {
    return new HasAuthorityAtomiumAdminAuthorization("my-admin-role");
}
```

The application additionally provides its own security filter chain (authentication) for `/rest/atomium/**`.

To reposition a feed (only when it is inactive and no run is in progress), the page and optionally the last
processed event suffice — you don't need to know the internal fetch optimization:
`PUT /rest/atomium/feed/{feedId}/feed-pointer` with body `{"pageLink": "/182", "eventId": "id-042"}` (the
next run resumes right after that event) or `{"pageLink": "/182"}` (reads the page from the start).

## The required DB table

The feed pointer is persisted in **`atomium_feed_pointer_v1`**, in the same transaction as the processing
of an entry. Per feed both the coordinates of the last processed event (`last_event_page_link` +
`last_event_id`) and the coordinate of the next fetch (`next_fetch_page_link` + `next_fetch_page_etag`)
are stored. The **application** provides the table (typically via Flyway); the library doesn't create it and
fails fast at startup when it is missing or doesn't have the expected schema.

```sql
CREATE TABLE IF NOT EXISTS atomium_feed_pointer_v1 (
    feed_id              text PRIMARY KEY,
    last_event_page_link text,
    last_event_id        text,
    next_fetch_page_link text        NOT NULL,
    next_fetch_page_etag text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL
);
```

## For maintainers

- The module is deliberately **independent of any environment-specific choice**: only the
  `FeedRestClientBuilders` seam (HTTP client/auth) is left open; content mapper (app `JsonMapper`),
  executor (daemon thread per feed) and backoff come as defaults. That keeps the module usable by any
  Spring Boot app.
- Depends on Spring Boot 4 (+ `spring-security-core` `optional`, only for the admin authorization seam on the
  admin endpoint), not on an application- or organization-specific library.
- The integration tests use WireMock (feed) and testcontainers/postgres (pointer persistence); `verify`
  requires a running Docker.
