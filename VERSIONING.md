# Versioning policy of the atomium-client suite

This document describes how the `atomium-client-*` libraries are versioned and how we deal with
different versions of external dependencies (Spring, Spring Boot, Jackson). Meant for **maintainers**
of the suite.

## Principles

1. **One suite version, lock-step.** All published modules share the same version (e.g. `1.4.0`) and
   release together — just like Spring Boot bumps all its starters together. The version means *"this
   generation of the suite's public API"*, nothing about Spring/Jackson.
2. **Semver on our own API.** MAJOR = breaking change in our API; MINOR = features; PATCH = bugfixes.
   Independent of the versions of external dependencies.
3. **Ports isolate the volatile part.** `atomium-client-core` has no external runtime coupling; all
   coupling lives in the thin adapters. If your stack is not supported, implement the port yourself
   (`FeedHttpClient` / `FeedPageDecoder`) — you then do not need the adapter.

## Two kinds of dependency evolution

| Type | Example | Approach |
| --- | --- | --- |
| **Evolutionary** (source-compatible) | Spring 6.1 → 6.2 → 7.0 | One artifact, compiled against the **lowest** supported version; an app on a newer version upgrades it transitively. |
| **Disruptive** (breaking, different packages) | Jackson 2 (`com.fasterxml`) → 3 (`tools.jackson`); Spring Boot 3 → 4 | Two **parallel artifacts**, with the variant in the `artifactId`. |

Hence:

- `atomium-client-core` — no external coupling, no variant.
- `atomium-client-jackson-3` — Jackson 3. A hypothetical Jackson 2 variant would be called `atomium-client-jackson-2`.
- `atomium-client-restclient` — one artifact, minimum **Spring 6.1** (the first version with `RestClient`).
- `atomium-client-spring-boot-4` — Spring Boot 4. A hypothetical Boot 3 variant would be called `atomium-client-spring-boot-3`.

In practice the Boot, Spring and Jackson worlds travel together (Boot 4 = Spring 7 + Jackson 3; Boot 3 =
Spring 6 + Jackson 2), so you typically pick one "world" and do not mix freely.

## Ship only what you serve

Ship only the variants you actually serve (YAGNI). Today that is the Boot 4 / Spring 7 / Jackson 3 world.
You add a second world only when there is real demand — in the meantime the ports let others adapt themselves.

## External versions (build)

The baseline versions of the external libraries are pinned as explicit properties in the suite parent
(the root `pom.xml`):

- `jackson.version` — the Jackson 3 version that `atomium-client-jackson-3` compiles against;
- `spring.version` — the Spring minimum version (6.1.x) that `atomium-client-restclient` compiles against;
- `junit.version` / `assertj.version` — test tooling.

So `atomium-client-restclient` compiles against the floor; an application on a newer Spring upgrades it
transitively. The `atomium-client-spring-boot-4` modules are tied to Spring Boot 4 and manage their
accompanying Spring, Jackson and test versions themselves.

## Bill of Materials

`atomium-client-bom` pins all published artifacts to one suite version. Consumers import it
(`scope=import`) instead of versioning every module individually. The demos are not in it (not a reusable artifact).

## Branches

- **Simultaneously supported worlds live side by side on `main`** as parallel artifacts (e.g.
  `spring-boot-3` + `spring-boot-4`), in the same reactor. No branch per stack (that leads to endless
  backporting).
- **You create a maintenance branch only when you drop a world** on `main` (e.g. Boot 3 EOL): the
  `*-3`/`jackson-2` modules disappear from `main` and get an `x.y-boot3` branch for bugfix-only releases.

## Adding a new major of a dependency (checklist)

1. Is it **evolutionary**? Bump the baseline property (or nothing) and test the range in CI. Done.
2. Is it **disruptive**? Create a parallel module with the variant in the `artifactId` (e.g.
   `atomium-client-jackson-2`), add it to `atomium-client-bom` and to the reactor. Both variants
   release together under the suite version.
