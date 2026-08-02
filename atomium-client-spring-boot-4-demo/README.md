# atomium-client-spring-boot-4-demo

> Part of the [`atomium-client` suite](../README.md) — a living example of the Spring Boot path.

Small Spring Boot app that runs `atomium-client-spring-boot-4` standalone. Shows that an application can use
the generic lib with only the **narrow seam** — a single `FeedRestClientBuilders` bean
(`DemoFeedRestClientBuilders`). Meant for local experimenting only.

## What it does

- Starts a local **postgres** via docker-compose (`compose.yml`, port 5801) — requires a running Docker.
- **Flyway** creates the table `atomium_feed_pointer_v1` (`db/migration/V001__…`).
- An in-memory **`DemoFeedEndpoint`** (`/demo-feed`) acts as the source feed: it grows by one entry on every
  poll, so you see the consumer process events automatically — no external feed server needed.
- The **`simple`** feed is active and logs every event one by one via `SimpleDemoFeedHandler` (an `EntryFeedHandler`
  on a raw `JsonNode`) — the simplest integration.
- The **`full-monty`** feed is inactive by default and shows what else is possible with an `EntryFeedHandler`:
  a **custom content DTO** (`MontyContent`) instead of a `JsonNode`, **`accepts`** filtering and
  **`pushEntry`**. It also documents all per-feed properties (`application.yml`) and the customizer SPI
  (`FullMontyDemoConfiguration`). Activate it during the demo (`active-on-startup: true` or via the admin endpoint):
  because `simple` already made the same feed grow, a **backlog** is waiting that gets processed right away.
- The **`simple-processing`** feed (also inactive by default) is the simplest possible **batch processing**: a
  `SimpleProcessingFeedHandler` on a raw `JsonNode` showing the two phases
  (`SimpleProcessingDemoFeedHandler`); the processing tuning lives under `atomium.feeds.simple-processing.processing.*`. Activate it
  and the backlog is visibly processed in batches.

## Running

Start `DemoApplication` (e.g. from the IDE, or `mvn spring-boot:run`). Inspect the feed pointer/status via the
admin endpoints under `/rest/atomium/**`.

## Security

Simple **HTTP Basic** with two in-memory users, plus the mandatory `AtomiumAdminAuthorization` bean that
determines who may access the admin endpoint (`SecurityConfig`):

- `feed-admin` / `feed-admin` — has the authority `feed-admin` and may access the admin endpoints.
- `viewer` / `viewer` — does not have it → gets a 403 (demonstrates the authorization).

The feed itself (`/demo-feed/**`) and the actuator (`/management/**`) are public.

## Troubleshooting

- **`No host port mapping found for container port 5432`** — the postgres container is running (`start-only` never
  stops it), but without a port mapping because host port 5801 was taken when it started. Clean up and restart:
  `docker rm -f atomium-client-spring-boot-4-demo-db-1` (check with `docker inspect … --format '{{json .NetworkSettings.Ports}}'`
  whether the mapping is now in place). Wipe completely: `docker compose down`.

## Start position of a new feed

Default `oldest` (full history). In `application.yml` you can change that to `now`
(only new events) or `pointer` + `page-link` (from a known page).
