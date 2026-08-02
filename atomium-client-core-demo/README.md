# atomium-client-core-demo

> Part of the [`atomium-client` suite](../README.md) — a living example of the path **without**
> `atomium-client-spring-boot-4`.

Small app that uses `atomium-client-core` directly: the fetch API and the handler API, with the `Feed` per
feed **assembled by hand** from the building blocks. Spring Boot serves here only as application setup (web
endpoint, properties, H2 datasource); the assembly code is exactly what any other stack would write too. Meant
for local experimenting only; **no Docker needed** (H2 in-memory).

## What it does

- An in-memory **`DemoFeedEndpoint`** (`/demo-feed`) acts as the source feed: it grows by one entry on
  every poll, so you see the consumers process events automatically — no external feed server needed.
- **`fetch`** (`GET /rest/demo/fetch`) — the low-level **fetch API** in its pure form: reads the complete feed
  from the oldest page up to the head in one classic read loop and returns the entries. No persistence, no
  scheduling — every request reads everything again.
- **`simple`** — the **simplest possible** handler integration (`SimpleDemoFeedHandler`, an `EntryFeedHandler`
  on a raw `JsonNode`): the assembly (`SimpleDemoConfiguration`) supplies the builder parameters, a
  start position and the poll settings; the building blocks are the defaults (in-memory pointers, no
  transactions). It is active and logs every event one by one.
- **`full-monty`** — **all building blocks explicit** (`FullMontyDemoConfiguration`): a real JDBC
  `FeedPointerRepository` (`DemoJdbcFeedPointerRepository`, table `demo_feed_pointer` in H2), real
  `FeedTransactions` (on Spring's `TransactionTemplate`), a custom backoff policy, the managed
  `PerFeedThreadExecutors` and extra `FeedEventListener`s. The handler is an `EntryFeedHandler` with a **custom content DTO** (`MontyContent`), `accepts` filtering and `pushEntry`.
- **`simple-processing`** — the simplest possible **two-phase processing**: a `SimpleProcessingFeedHandler`
  on a raw `JsonNode` showing the two phases (`SimpleProcessingDemoFeedHandler`); the assembly
  (`SimpleProcessingDemoConfiguration`) is the minimal one plus the builder's two processing knobs.
- The **`SimpleFeedScheduler`** (from core) polls the active feeds every `demo.query-interval`; the
  `Feeds` registry and the **`DemoControlEndpoint`** (`/rest/demo/feeds`) drive them.

## Running & demo scenario

Start `CoreDemoApplication` (e.g. from the IDE, or `mvn spring-boot:run`). All requests are ready to go in
[`src/main/http/demo.http`](src/main/http/demo.http).

1. Watch the logs: the `simple` feed processes a new event every ~5s.
2. `GET /rest/demo/fetch` — the fetch API reads the whole feed from start to finish.
3. `PUT /rest/demo/feeds/full-monty/activate` — the `full-monty` immediately processes the accumulated
   backlog; after that it simply keeps polling.
4. `PUT /rest/demo/feeds/full-monty/deactivate` and activate again: thanks to the JDBC pointer it resumes
   exactly where it stopped (the `simple` feed does restart from scratch after an app restart — in-memory default).
5. `POST /rest/demo/feeds/full-monty/push` with `{"aField": "recovered-042"}` — the `EntryPusher` building
   block: process a loose content item as if it had been on the feed.
6. `PUT /rest/demo/feeds/simple-processing/activate` — the same backlog, but now processed **in batches**
   (visible in the logs).

## Start position of a new feed

All assemblies use `pointerToOldest` (full history). Alternatives in the builder:
`pointerFromNow` (only new events) or `() -> new FeedPointer(pageLink)` (from a known page).
