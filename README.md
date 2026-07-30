# atomium-client

A suite of Java libraries for consuming an **Atomium feed** from an application. The core is pure Java;
there are thin adapters for Jackson and Spring, and a Spring Boot integration layer that takes most of the
work off your hands. Each of these three stacks — pure Java, Spring, Spring Boot — is fully supported; pick
the path below that fits you.

## What is an Atomium feed?

An Atomium feed is a **paginated, Atom-like event feed** (in JSON) published by a source system. It is an
integration pattern for asynchronous communication: the source system writes events to the feed, the
consumer **polls** the feed and processes every new event.

- Once published, an event **never** changes. New events arrive on the **head** (the youngest page); that
  page grows until it is full and then becomes immutable.
- The pages are linked to each other (older ↔ younger). A consumer that wants the full history starts at the oldest
  page and works towards the head; once there, they keep polling for new events.
- The content of an event is a piece of JSON defined by the source system (your domain type).

A consumer remembers where it left off (which page, which last event) through a **feed pointer**, so that
after a restart it resumes instead of reprocessing everything.

This suite takes care of the pagination, the pointer persistence, the scheduling, the retries/backoff and
the HTTP work (how much exactly depends on the path you choose below). Your application always provides at
least: **what to do with each event** and **how to reach the feed**.

## The modules

From low to high (each module builds on the previous one):

| Module | Role | Do you need it directly? |
| --- | --- | --- |
| [`atomium-client-core`](atomium-client-core/README.md) | Pure Java: the protocol model, the low-level **fetch API** (`AtomiumClient`, pagination + pointer logic, ports `FeedHttpClient`/`FeedPageDecoder`) and the high-level **handler API** (`FeedHandler` SPI with batches, transactions, pointer persistence, backoff and events). No Spring, no Jackson. | On the pure-Java and the Spring path. |
| [`atomium-client-jackson-3`](atomium-client-jackson-3/README.md) | Jackson adapters: `JacksonFeedPageDecoder` (parses the Atomium JSON envelope) and `JacksonFeedContentDecoder` (derives the content type from your `FeedHandler`). | On the pure-Java and the Spring path (unless you write your own decoders). |
| [`atomium-client-restclient`](atomium-client-restclient/README.md) | `FeedHttpClient` adapter on top of Spring's `RestClient`. | On the Spring path. |
| [`atomium-client-spring-boot-4`](atomium-client-spring-boot-4/README.md) | Spring Boot auto-configuration: discovers your `FeedHandler` beans, does polling, pointer persistence (JDBC), backoff and an optional admin endpoint. Brings core, jackson-3 and restclient along transitively. | On the Spring Boot path — the only dependency you need then. |
| [`atomium-client-core-demo`](atomium-client-core-demo/README.md) | Standalone demo of the path **without** the Boot module: the fetch API used directly, and the handler API assembled by hand (minimal and with all building blocks). H2 in-memory, no Docker needed. | No — only as a living example. |
| [`atomium-client-spring-boot-4-demo`](atomium-client-spring-boot-4-demo/README.md) | Standalone, locally runnable demo of the Spring Boot path (in-memory feed, HTTP Basic security, postgres via docker-compose). | No — only as a living example. |

> New to the code? [`DESIGN.md`](DESIGN.md) is a short map-with-glossary of the most important classes per module,
> in a logical order — handy to get your bearings and to look up abstractions quickly.

## Versions & compatibility

Some artifacts carry a suffix for the version of an external dependency they support — e.g.
`-jackson-3` (Jackson 3) and `-spring-boot-4` (Spring Boot 4). That is because such a major jump is not
source-compatible; if we later support another one, a parallel artifact appears next to it (e.g.
`atomium-client-jackson-2`). `atomium-client-restclient` has no suffix: a single artifact covers Spring 6.1+.

All artifacts share a single suite version and release together. Import the **`atomium-client-bom`** to pin
them consistently without versioning them one by one. The full policy is in
[VERSIONING.md](VERSIONING.md).

## Getting started

Pick the path that fits your stack. Each path is deliberately kept short; the full explanation is always in
the README of the module involved.

### Getting started — pure Java application

You use [`atomium-client-core`](atomium-client-core/README.md) (the protocol + the two APIs) and
[`atomium-client-jackson-3`](atomium-client-jackson-3/README.md) (the JSON decoder). You provide your own
`FeedHttpClient` — a single GET method, e.g. on top of `java.net.http.HttpClient` — and then choose:

- **the fetch API**: wire up the `AtomiumClient` and drive the poll loop and the persistence of the
  `FeedPointer` yourself;
- **the handler API**: implement a `FeedHandler`, bundle it with your building blocks into a `Feed` and let
  the framework take care of the traversal, the batches, the transactions, the backoff, the events and the
  scheduling (the bundled `SimpleFeedScheduler`).

```java
var client = new AtomiumClient(myFeedHttpClient, new JacksonFeedPageDecoder());
```

→ The full code examples (the poll loop and the handler path) and the protocol explanation are in the
[`atomium-client-core` README](atomium-client-core/README.md). A complete, running example of this path is
[`atomium-client-core-demo`](atomium-client-core-demo/README.md) — it uses Spring Boot only as a bootstrap
shell (web server + properties); the atomium integration itself is pure Java there.

### Getting started — Spring application (without Spring Boot)

Besides core + jackson you use [`atomium-client-restclient`](atomium-client-restclient/README.md): it provides
the `FeedHttpClient` on top of a `RestClient` that you configure yourself (base URL, auth, retries,
timeouts, …). Both APIs are then open to you:

- **the fetch API**: wire up the `AtomiumClient` as a bean and take care of the poll loop (e.g. `@Scheduled`)
  and the pointer persistence yourself;
- **the handler API**: assemble a `Feed` bean per feed — with a `FeedPointerRepository` on your own
  persistence and `FeedTransactions` on your `TransactionTemplate` — and let the framework take care of the
  traversal, the batches, the transactions, the backoff, the events and the scheduling.

```java
var restClient = RestClient.builder().baseUrl("https://source/myfeed").build();
var client = new AtomiumClient(new SpringFeedHttpClient(restClient), new JacksonFeedPageDecoder());
```

→ Details in the [`atomium-client-restclient` README](atomium-client-restclient/README.md); the poll loop and
the handler path are in the [`atomium-client-core` README](atomium-client-core/README.md). The Spring
implementations of the building blocks are demonstrated in the full-monty assembly of
[`atomium-client-core-demo`](atomium-client-core-demo/README.md) (`SpringTransactionFeedTransactions`,
`DemoJdbcFeedPointerRepository`).

### Getting started — Spring Boot application

One dependency — [`atomium-client-spring-boot-4`](atomium-client-spring-boot-4/README.md) — brings the rest
along transitively and auto-configures the complete consumer: polling, pointer persistence (JDBC), backoff,
metrics (Micrometer, if present) and an optional admin endpoint. You only provide:

1. an `EntryFeedHandler` bean — what to do with each event;
2. a `FeedRestClientBuilders` bean — how to reach the source feed;
3. some config under `atomium.feeds.<feedId>`;
4. the table `atomium_feed_pointer_v1` (e.g. via Flyway).

→ The full step-by-step with all properties, the `FeedCustomizer` SPI and the admin endpoint is in the
[`atomium-client-spring-boot-4` README](atomium-client-spring-boot-4/README.md). A complete, running example
is [`atomium-client-spring-boot-4-demo`](atomium-client-spring-boot-4-demo/README.md).

## Building

Java 21 or higher (the artifacts target Java 21, `maven.compiler.release`). Build everything with the Maven
wrapper:

```
./mvnw verify
```

Optionally, [mise](https://mise.jdx.dev) users get the exact JDK the project is developed against with
`mise install` (pinned in `.mise.toml`); without mise, any JDK 21+ works.

The integration tests of the spring-boot modules use testcontainers (postgres), so a running Docker daemon is
needed for `verify`.

## Releasing on Maven Central

Every direct push to `main` runs the complete Maven build and, after it succeeds, publishes the current
`-SNAPSHOT` version to the Maven Central snapshot repository. Pull requests and manually dispatched CI runs
only build and test. The publishing step refuses to run when the version in `pom.xml` does not end in
`-SNAPSHOT`.

Releases are driven by Git tags. The tag must start with `v`; the part after `v` becomes the version of every
module in the release. For example, tag `v4.0.0` publishes version `4.0.0`. To release:

1. Make sure the commit to release is on `main`, its CI build is green, and the checked-in project version is
   still a `-SNAPSHOT`.
2. Create and push an annotated version tag:

   ```bash
   git tag -a v4.0.0 -m "Release 4.0.0"
   git push origin v4.0.0
   ```

3. Follow the `Release` GitHub Actions workflow. It derives the release version from the tag, builds and tests
   the complete reactor, signs the published artifacts, and automatically publishes them through the Maven
   Central Portal.
4. After the release succeeds, set the suite to the next development version, commit the changed POMs, and
   push them to `main`. For example:

   ```bash
   ./mvnw org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
     -DnewVersion=4.0.1-SNAPSHOT \
     -DgenerateBackupPoms=false
   ```

The repository must have `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, and the base64-encoded `GPG_PRIVATE_KEY`
configured as GitHub Actions secrets. The release workflow changes the Maven version only in its checkout;
it does not change or commit the version in the repository.

## License

[MIT](LICENSE).
