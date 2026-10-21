# Changelog

All notable changes to the atomium-client suite. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
the suite follows [semantic versioning](VERSIONING.md), and all modules share one version.

This file is written for **users** of the suite: what they notice when they upgrade, and what they have to
check or change (`Upgrade notes`). Internal refactorings and test work do not belong here.

## [Unreleased]

### Added

### Changed

### Fixed

### Removed

### Upgrade notes

## [4.1.0] - 2026-10-21

### Added

- `FeedRuntime.whileStopped(work)` runs work while the feed is guaranteed to be stopped and restores the feed
  afterwards; `moveFeedPointer`, `resetToOldest` and `resetFromNow` build on it. The wait is bounded by the
  new `stopTimeout` (`Feed.Builder.stopTimeout`, `atomium.feeds.<id>.stop-timeout`, default 60 s). See the
  core README, "Moving the feed pointer from application code".
- Admin endpoint: `PUT /rest/atomium/feed/{feedId}/feed-pointer` accepts `{"type": "OLDEST"}` and
  `{"type": "NOW"}` next to the known-position body (`type: POINTER`, the default), and returns the pointer as
  persisted.
- A runtime-wide `enabled` switch on `Feeds`: does this instance consume feeds at all, next to the per-feed
  `active` state (is the feed wanted). `new Feeds(runtimes, enabledOnStartup)`, `feeds.enable()`,
  `feeds.disable()`, `feeds.disableAndAwait(timeout)`, `feeds.isEnabled()`; a `FeedRunner` starts disabled and
  runs only once its registry enables it. In spring-boot-4: `atomium.runtime.enabled-on-startup` (default
  `true`). For an application with several instances, of which one consumes (a dedicated profile, or a leader
  election). The `feedActivated`/`feedDeactivated` events — and so the Micrometer gauges — follow the effective
  state `active && enabled`, so a standby instance carries no `atomium.feed.last.success.time` series. The
  health indicator shows `enabled` as a detail (a disabled runtime is `UP`). The core demo exposes the switch.

### Changed

- The default `processing.max-size` (`FeedDefaults.MAX_PROCESSING_SIZE`, the maximum batch size of a
  `SimpleProcessingFeedHandler` feed without an explicit value) is now `1` instead of `100`: one entry per
  batch, the only size that is safe for every handler. The spring-boot-4 README explains how to choose the
  size per feed.
- Admin endpoint: `PUT …/feed-pointer` is allowed on an active feed (briefly stopped for the move; previously
  400) and answers **409 Conflict** when the run in flight does not stop within the feed's `stop-timeout`.
  `PUT …/deactivate` waits up to that `stop-timeout` (previously a fixed 10 s).
- `AtomiumFeedProperties.InitialFeedPointer.Type` is now the top-level `FeedPointerType` (spring-boot-4), shared
  with the admin command.
- Admin endpoint: on an instance whose runtime is disabled every operation, the reads included, answers
  **503 Service Unavailable** with a `Retry-After` header.
- The per-feed startup lines (INFO for an active feed, WARN for an inactive one) are logged when `Feeds`
  enables the runtime — at startup, or on a later `enable()` — instead of by the scheduler; the schedulers log
  one INFO that they tick, and do nothing while the runtime is disabled.

### Upgrade notes

- Set `processing.max-size` explicitly on every `SimpleProcessingFeedHandler` feed (`atomium.feeds.<id>.processing.max-size`,
  or `Feed.Builder.maxProcessingSize` on the hand-assembled path). A feed that relied on the old default
  now processes one entry per batch, with one transaction and one pointer commit per entry; set `100` to keep
  its previous behaviour.
- Replace hand-rolled stop/move/restart code (`deactivateAndAwait`, `pointerRepository().save`, `activate`)
  by the `FeedRuntime` operations.
- Tooling that relied on a feed-pointer PUT on an active feed being refused (400) now gets 200.
- Code that constructs `InitialFeedPointer` itself imports `FeedPointerType` instead of the nested `Type`.
- Set `atomium.feeds.<id>.stop-timeout` (or `Feed.Builder.stopTimeout`) for a feed whose handler can spend more
  than 60 s on one entry plus one batch.
- A `FeedRuntime` runs only once it is in a `Feeds` registry: a `FeedRunner` starts disabled and only the
  registry enables it. Code that assembles a runtime with `FeedRuntime.of(feed)` and drives
  `runner().tryToStart()` itself, without a registry, now gets an `IllegalStateException` that says so; wrap
  the runtime in `new Feeds(List.of(runtime))`, which is enabled by default. Applications on the Spring Boot
  module, and core applications that already use `Feeds`, need no change.
- An application that deactivates and reactivates its feeds itself to follow a leader election can replace
  that with `feeds.disable()`/`feeds.enable()` and `enabled-on-startup: false`.

## [4.0.0] - 2026-08-13

First release of the rewritten suite: a pure-Java core with thin adapters, replacing the RxJava-based
`atomium-client-v2` line. Not source-compatible with it; a migration is a re-implementation per feed on the
handler API below.

### Added

- `atomium-client-core`: the Atomium protocol model and two APIs. The low-level **fetch API**
  (`AtomiumClient`, page navigation, feed pointers, ports `FeedHttpClient` / `FeedPageDecoder`) and the
  high-level **handler API**: a `FeedHandler` SPI, either per entry (`EntryFeedHandler`, commit per entry) or
  per batch in two phases (`SimpleProcessingFeedHandler`: `process` outside the transaction, `persist` inside
  it, atomically with the feed pointer), with an optional `afterCommit` hook for best-effort side effects
  after a durable commit.
- `atomium-client-core`: a `Feed` builder and `FeedRuntime` that assemble the consumer: pointer persistence
  (`FeedPointerRepository`), transactions (`FeedTransactions` port), exponential backoff on failed runs, a
  page safety net (`maxUncommittedPages`) that bounds the work between two commits, and a `FeedEventListener`
  event model (pointer commits, run completion and failure, activation and deactivation, after-commit
  outcome) with a bundled logging listener.
- `atomium-client-jackson-3`: `JacksonFeedPageDecoder` (the Atomium JSON envelope) and
  `JacksonFeedContentDecoder` (the entry content, typed from the handler's type parameter), on Jackson 3.
- `atomium-client-restclient`: `FeedHttpClient` on Spring's `RestClient` (Spring 6.1 or newer), with
  conditional requests (`ETag` / `If-None-Match`) and per-feed query parameters.
- `atomium-client-spring-boot-4`: Spring Boot 4 auto-configuration. One `Feed` per `FeedHandler` bean,
  configured under `atomium.feeds.<id>.*` (`url`, `query-params`, `active-on-startup`, `query-interval`,
  `initial-feed-pointer`, `backoff`, `processing.*`); the app provides a `RestClient.Builder` per feed through
  `FeedRestClientBuilders` and can vary a feed with a `FeedCustomizer`. JDBC pointer persistence in the table
  `atomium_feed_pointer_v1`, Spring transactions, a scheduler, Micrometer metrics (`atomium.*`, including the
  staleness gauge `atomium.feed.last.success.time`), a health indicator, and an optional admin endpoint under
  `/rest/atomium/**` (`atomium.admin.enabled`, authorization through the app's `AtomiumAdminAuthorization`
  bean): feed status, configuration, raw pages, activate, deactivate, move the feed pointer, trigger a run.
- `atomium-client-bom`: pins all published artifacts to the suite version.
- Two runnable demos: `atomium-client-core-demo` (fetch API and hand-assembled handler API, H2, no Docker)
  and `atomium-client-spring-boot-4-demo` (the Boot path end to end, postgres via docker-compose).

### Upgrade notes

- Coming from `atomium-client-v2`: re-implement each consumer as a `FeedHandler`, persist the feed pointer in
  `atomium_feed_pointer_v1` (migrate the old position as the page link of the next fetch), and move the
  consumer's configuration to `atomium.feeds.<id>.*`. Metrics and health come from the suite; replace the
  consumer's own gauges and health checks.
- `processing.max-size` and `processing.max-uncommitted-pages` are only valid on a `SimpleProcessingFeedHandler`
  feed; on any other handler the application fails at startup.
- The admin endpoint refuses to start (`atomium.admin.enabled=true`) without an `AtomiumAdminAuthorization`
  bean.

[Unreleased]: https://github.com/WegenenVerkeer/atomium-client/compare/v4.1.0...HEAD
[4.1.0]: https://github.com/WegenenVerkeer/atomium-client/compare/v4.0.0...v4.1.0
[4.0.0]: https://github.com/WegenenVerkeer/atomium-client/releases/tag/v4.0.0
