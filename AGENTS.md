# Atomium client — context for an agent

> This is **not** the library documentation. The real documentation lives elsewhere; read it and don't repeat it here:
> - **`README.md`** — overview, "what is an Atomium feed", getting started per stack (pure Java / Spring /
>   Spring Boot).
> - **per-module READMEs** —
>   `atomium-client-{core,jackson-3,restclient,core-demo,spring-boot-4,spring-boot-4-demo}/README.md`.
> - **`VERSIONING.md`** — versioning policy.
> - **`DESIGN.md`** — a short map-with-glossary of the most important classes per module (orientation).
> - **javadoc + code comments** — the API details.
>
> This document exists solely to **kickstart an agent session (or reviewer)**: the non-obvious whys, the
> invariants and the pitfalls — things you won't get from a first read of the code. For everything else: the
> docs above + the code. It describes the **AS-IS**; it is not a log of past refactorings.

## What & where

A suite of Java libraries for consuming an **Atomium feed** from an application. Structure:

```
atomium-client/
├── atomium-client-bom
├── atomium-client-core         ← pure Java, two APIs: fetch (client.fetch: AtomiumClient, pointers;
│                                  protocol + ports as siblings) and handler (client.handler: FeedHandler SPI,
│                                  consumer/runner pipeline, Feed builder + FeedRuntime, FeedTransactions port)
├── atomium-client-jackson-3    ← FeedPageDecoder + FeedContentDecoder on Jackson 3
├── atomium-client-restclient   ← FeedHttpClient on Spring RestClient (min Spring 6.1)
├── atomium-client-core-demo    ← standalone demo of the path without the Boot module: raw fetch API +
│                                  hand-assembled handler API (H2 in-memory, no Docker; port 14220)
├── atomium-client-spring-boot-4 ← Spring Boot 4 auto-config: assembles a Feed per FeedHandler bean
│                                  (RestClient seam, JDBC pointer persistence, transactions), plus scheduler,
│                                  admin endpoint and metrics (Micrometer). THE module for a user.
└── atomium-client-spring-boot-4-demo ← standalone locally runnable demo
```

Packages are `be.wegenenverkeer.atomium.client.*` throughout. All modules share a single suite version,
lock-step (see `VERSIONING.md`).

## Building / testing (important)

- **Java baseline 21** (`maven.compiler.release`); the build itself runs on the JDK pinned by `.mise.toml`
  (corretto-25) — with [mise](https://mise.jdx.dev): `mise install`, then just build; without mise: provide a
  JDK 21+ yourself. New code must therefore not use language or API features beyond Java 21.
- Build via the **Maven wrapper**: `./mvnw verify`.
- **Docker required** (testcontainers postgres + WireMock). The demo starts a postgres on port 5801 (fixed port →
  on a "No host port mapping" error: `docker rm -f` the old container; see the demo README troubleshooting).
- **Running the demo locally:** first `./mvnw install -DskipTests` (otherwise `spring-boot:run` picks up a stale
  lib jar from `.m2` → `NoClassDefFound`), then `./mvnw -pl atomium-client-spring-boot-4-demo spring-boot:run`.
  Metrics visible at `/management/prometheus` and `/management/metrics` (feed `simple` is active; `full-monty`
  and `simple-batched` inactive, activate via admin).
- **Do not commit** unless explicitly asked; the developer reviews and commits themselves.

## Non-obvious whys (brief; details in the docs + javadoc)

- **Module split.** `atomium-client-core` is deliberately pure Java without framework dependencies; Jackson and
  Spring live in separate adapter modules on the core ports, and the Boot module assembles the whole. See the
  READMEs + VERSIONING.md.
- **The narrow seam `FeedRestClientBuilders`.** The only thing an app is required to provide: a
  `RestClient.Builder` per feed. Content mapper/executor/backoff are framework defaults, overridable per feed
  via `FeedCustomizer`.
- **FeedPointer model.** `FeedPointer(@Nullable EventCoordinate lastEvent, FetchCoordinate nextFetch)`.
  `EventCoordinate(pageLink, eventId)` = last processed event (sticky; `null` before the first event).
  `FetchCoordinate(pageLink, filterEventId, etag)` = where the next fetch starts. Constructors:
  `FeedPointer(pageLink)` (genesis), `FeedPointer(lastEventPageLink, lastEventId, nextFetchPageLink,
  nextFetchPageEtag)` (from persistence; derives `filterEventId`), factory `resumeAfter(EventCoordinate)`.
  Table `atomium_feed_pointer_v1`, columns `feed_id, last_event_page_link, last_event_id,
  next_fetch_page_link, next_fetch_page_etag, created_at, updated_at` (the filter is derived, not stored).
- **Admin endpoint** (`/rest/atomium/**`, in **spring-boot-4**, incl. its tests with in-memory HTTP Basic
  security). Authorization via the mandatory `AtomiumAdminAuthorization` bean (fail-fast on
  `atomium.admin.enabled=true` without a bean; every endpoint method asserts read or write permission as its
  first statement). GETs = read-only diagnostics (feed pointer, status, config, raw page). Mutating:
  `PUT …/feed-pointer` (`SetFeedPointerCommand(pageLink, eventId?)` → `resumeAfter` with an eventId, otherwise
  `new FeedPointer(pageLink)`; empty pageLink → 400; only when the feed is inactive and no run is in progress),
  `PUT …/activate`, `PUT …/deactivate` (deactivates and waits, bounded, until the running run has stopped —
  `FeedRunner.deactivateAndAwait`; that way a subsequent feed-pointer PUT or application cleanup cannot collide
  with a still-running run), `POST …/push`. (No DELETE.) Every mutating call logs at INFO.

## Architecture of the handler API (core) + spring-boot-4 (invariants & pitfalls)

The heart is the **batch processing** and the **event model**; below is what you won't immediately derive from
the code. See `DESIGN.md` for the class overview and the javadoc for the details.

- **Two handler variants, one controller.** The developer implements `EntryFeedHandler` (per entry, the common
  case) or `BatchedFeedHandler` (per deduplicated batch, for burst feeds). Both run through the **same**
  `BatchedFeedHandlerController`: an `EntryFeedHandler` becomes a **batch-of-1** without dedup via
  `EntryFeedHandlerAdapter`. That explains why there is an adapter and only one controller.
- **The batch owns the mutable state, not the bean.** `FeedHandlerBatch` (created fresh per run/flush via
  `startBatch(size)`) accumulates and decides for itself via `isComplete()` when it is done → the handler bean
  stays stateless. `DefaultFeedHandlerBatch` dedups on a `keyExtractor` (last-wins, first-seen order), complete
  on the number of distinct keys. **Dedup is per batch** (not cross-window) → processing should be idempotent.
- **The controller reports a `Code`, the consumer decides about the transaction.** After every callback
  `FeedHandlerController` returns a buffer state (`BUFFERING` / `BUFFER_COMPLETE` / `BUFFER_EMPTY`);
  `FeedConsumerImpl` translates that into what happens to the tx and the pointer.
- **Pitfall in the controller:** "batch not empty" has **two** answers that are **not** interchangeable — in the
  middle of the feed (filtered-out entry, page boundary) it is `BUFFERING` (keep buffering), at the end of a run
  (`onEndOfFeed`/`onInterrupted`) it is `BUFFER_COMPLETE` (flush the rest). Confusing them = a flush on every
  page boundary.
- **Transaction & pointer invariant.** The buffer is transaction-free; a short transaction only opens on the
  flush — **never an open DB tx during an HTTP fetch**. `FeedConsumerImpl` uses the `FeedTransactions` port
  (Boot impl on the regular `TransactionTemplate`; no manual tx). Two pointers: `pendingPointer` (where we would
  be) vs `persistedPointer` (what is in the DB).
  As long as the batch is uncommitted, the persisted pointer stays **pinned** → a crash re-reads that batch. No
  needless writes (skip when pending == persisted, e.g. on a 304). Safety net against an unbounded re-read
  window: `batch.max-unflushed-pages` forces a commit after N pages without a flush.
- **Run timing: the runner decides, the scheduler ticks dumbly and frequently.** After every run the
  `FeedRunner` remembers a `nextRun` (on success `now + queryInterval`, on failure `now + backoff`);
  `tryToStart` refuses until that moment. The schedulers (`SimpleFeedScheduler` in core, `FeedScheduler` in
  spring-boot-4) schedule **one** shared tick for all feeds (default every second; test constructor with an
  injectable scheduler and tick interval — the Boot `FeedScheduler` owns its own single-thread pool and is
  deliberately **not** a `TaskScheduler` bean, so Boot's default for `@Scheduled` stays intact) — not per feed
  on the query interval. **Why:** with one tick per query interval a retry only happens after
  `backoff + queryInterval` worst case (the tick just before the backoff deadline gets refused → a whole extra
  interval of waiting). `activate()` (and thus the admin activate too) calls `scheduleNextRunNow()` — a human
  intervening does not wait for a deadline. If you change anything here: the query-interval wait also applies to
  manual `tryToStart` calls; tests and tooling that want "a run right now" go via
  `activate()`/`scheduleNextRunNow()`.
- **Event emission — the most important invariant for anyone touching the event flow.** *All*
  `FeedEventListener` events are emitted by `FeedConsumerImpl`, each time **after** the commit point (never
  inside a tx). The controller does **not** emit itself — it *reports*: `flush()` returns the actually processed
  entries (post-`accepts`, post-dedup) and `result()` the counters (read/accepted/processed);
  `feedPointerAdvanced` carries per commit the delta counters since the previous commit (so metrics also count
  mid-way through a long run, without loss on a later failure).
  The only exception: `runFailed` comes from `FeedRunner` (which knows the backoff).
  Logging (`LoggingFeedEventListener`) and metrics (`MicrometerFeedEventListener`) are both just listeners — by
  design.
- **Health.** `AtomiumFeedHealthIndicator` per feed under a single contributor "atomium"
  (`AtomiumHealthAutoConfiguration`, conditioned on the health classes — `spring-boot-health` is an *optional*
  dep — and `atomium.health.enabled`). Pull-based on the `FeedRuntime` state (runner + the internal progress
  listener with last commit/event): DOWN from `failure-threshold` (default 3) consecutive failures; inactive =
  deliberately UP. Belongs in monitoring, not in the liveness/readiness probes (README).
  The demo context test asserts the wiring (same silent-failure safety net as with metrics).
- **Metrics.** `MicrometerFeedEventListener` + `AtomiumMetricsAutoConfiguration`, conditioned on a
  `MeterRegistry` (`micrometer-core` is an *optional* dep; disable via `atomium.metrics.enabled=false`).
  **Pitfall:** the autoconfig must order after Boot's `CompositeMeterRegistryAutoConfiguration` (moved in Boot 4
  to `org.springframework.boot.micrometer.metrics.autoconfigure`), otherwise `@ConditionalOnBean(MeterRegistry)`
  sees nothing. That fails **silently** in a plain unit test → the demo context test explicitly asserts that the
  listener is wired.
- **Config** under `atomium.feeds.<id>.*`: `url`, `active-on-startup`, `query-interval`,
  `initial-feed-pointer`, `backoff`, and `batch.{preferred-batch-size, max-unflushed-pages}`. A
  `preferred-batch-size` on an `EntryFeedHandler` → **fail-fast at startup** (the threshold there is always 1,
  so it would be a silent no-op).
- **Visibility.** Most internal machinery is deliberately **package-private**; a few types are public solely
  because the `admin` subpackage, the demos, or a `@ConditionalOnMissingBean` override needs them. Keep new
  internal stuff package-private. In **core**, public concrete classes are **final unless extension is an
  intended extension point** — only `DefaultFeedHandlerBatch` and `LoggingFeedEventListener` (each with a
  comment saying so) and the deliberately open exception hierarchy. In **spring-boot-4** the assembly/admin
  classes are not final (Spring configuration, `@ConditionalOnMissingBean` overrides).
- **Defaults in one place.** Core behavior defaults in `FeedDefaults` (batch numbers), Boot binding defaults as
  constants in `AtomiumFeedProperties.Defaults` (directly in the `@DefaultValue` annotations). Documentation
  references them with `{@value}` so it cannot drift — except markdown: the config table in the spring-boot-4
  README must be updated by hand on a change.
- **Pitfall: the content-type resolution (`JacksonFeedContentDecoder` in `-jackson-3`).** The content type `C`
  of a handler passes through an intermediate interface (`EntryFeedHandler<C> extends FeedHandler<C>`) where it
  is a type *variable*; whoever does the resolution naively on the raw `java.lang.reflect.Type` lets Jackson
  decode to a `LinkedHashMap` → `ClassCastException` in the handler. The resolution therefore happens entirely
  inside Jackson (`TypeFactory.findTypeParameters`); `JacksonFeedContentDecoderTest` covers exactly this case.
  Don't fall into this again if you touch the type resolution.

## Documentation convention

**The documentation is a handbook, not a logbook.** User documentation (READMEs, `DESIGN.md`, javadoc)
describes the **final API** for whoever uses the lib — not the history of refactorings, not the technical
choices made, not this iteration or conversation. Concretely:

- **No** design rationale or layer-crossing explanation in javadoc ("this lives in core because…", "the
  assembly layer additionally does…", "this used to be…"). A user has no use for that, and even for a
  maintainer such a thing does not deserve the weight of javadoc.
- Rationale a maintainer needs on the spot (why this check, why this order) belongs in a **code comment on the
  line in question** — short. Anything bigger (invariants, pitfalls, whys) belongs here in `AGENTS.md`, not in
  the user docs.
- Javadoc says what a type/method **does and how to use it** (contract, parameters, defaults, fail-fast
  behavior); error messages and docs in core stay framework-neutral.
- When in doubt: would a new user learning the final API get anything out of this? No → no javadoc.

## Testing convention

Preferably **high-level functional**, at two levels with the same `@Nested` theme layout (EntryFeedHandler /
BatchedFeedHandler / Events / Interruption / failure paths / safety net): the **primary, fine-grained
functional spec** is `FeedConsumerTest` in core (handler API on the real fetch API on top of
`FakeFeedHttpClient`; no HTTP/Spring, synchronous runs via an inline executor, `RecordingFeedTransactions`
counts commits/rollbacks); `FeedConsumerWireMockTest` in spring-boot-4 (WireMock feed + testcontainers
postgres) proves the same scenarios **end-to-end through the whole stack** (autoconfig → HTTP → JDBC →
transactions). New pure-consumer scenarios belong in core; only what touches the Boot shell must also go in the
IT. Pure logic and conditions in targeted unit tests (`MicrometerFeedEventListenerTest`;
`AtomiumMetricsAutoConfigurationTest` via `ApplicationContextRunner`). Handy tricks: force an *interruption*
with `InterruptingFeedEventListener` (calls `runner.deactivate()` from a listener callback); a *304* with an
`ETag` stub + a second stub on `If-None-Match`; the *failure path* (rollback) with `handler.failAt(id)`; the
*pointer positions* per commit via `RecordingFeedEventListener.pointerCommits()`.

## Logging convention

A `FeedEventListener` logs **observable feed events**; the **framework** logs its own lifecycle/control flow
(`FeedRunner` the run timing — INFO "run completed; next run after …" on success, the failure ERROR + backoff —
and the activate/deactivate/recovery INFO; the schedulers (`FeedScheduler`, `SimpleFeedScheduler`) at startup
INFO for an active feed and **WARN** for an inactive one; the admin endpoint every mutating PUT/POST at INFO).
So add lifecycle or control-flow logging in the framework class, not in the `LoggingFeedEventListener`.
