# Changelog

All notable changes to the atomium-client suite. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
the suite follows [semantic versioning](VERSIONING.md), and all modules share one version.

This file is written for **users** of the suite: what they notice when they upgrade, and what they have to
check or change (`Upgrade notes`). Internal refactorings and test work do not belong here.

## [Unreleased]

### Added

### Changed

- The default `processing.max-size` (`FeedDefaults.MAX_PROCESSING_SIZE`, the maximum batch size of a
  `SimpleProcessingFeedHandler` feed without an explicit value) is now `1` instead of `100`: one entry per
  batch, the only size that is safe for every handler. The spring-boot-4 README explains how to choose the
  size per feed.

### Fixed

### Removed

### Upgrade notes

- Set `processing.max-size` explicitly on every `SimpleProcessingFeedHandler` feed (`atomium.feeds.<id>.processing.max-size`,
  or `Feed.Builder.maxProcessingSize` on the hand-assembled path). A feed that relied on the old default
  now processes one entry per batch, with one transaction and one pointer commit per entry; set `100` to keep
  its previous behaviour.

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

[Unreleased]: https://github.com/WegenenVerkeer/atomium-client/compare/v4.0.0...HEAD
[4.0.0]: https://github.com/WegenenVerkeer/atomium-client/releases/tag/v4.0.0
