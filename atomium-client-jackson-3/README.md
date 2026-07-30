# atomium-client-jackson-3

> Part of the [`atomium-client` suite](../README.md) — see there for the overview and the integration paths.

Jackson adapter for [`atomium-client-core`](../atomium-client-core/README.md), on top of Jackson 3
(`tools.jackson`), with two decoders:

- **`JacksonFeedPageDecoder`** — implementation of the port `FeedPageDecoder`: parses the
  Atomium JSON envelope (links, metadata, entries) into the core records and keeps `content.value` **raw**
  (a String), so the consumer can deserialize per entry itself.
- **`JacksonFeedContentDecoder`** — for the handler API: derives the content type `C` from the
  type hierarchy of your `FeedHandler` (you never have to repeat it) and supplies the
  `FeedContentDecoder<C>` that deserializes the raw entry content with your `JsonMapper`.

## When do you (not) need this module?

- **You do**, if you don't use Spring Boot but do use Jackson, and you wire the `AtomiumClient` by hand.
- **You don't**, if you use [`atomium-client-spring-boot-4`](../atomium-client-spring-boot-4/README.md): it
  brings this module in transitively and wires the decoder for you.

## Usage

```java
FeedPageDecoder decoder = new JacksonFeedPageDecoder();
var client = new AtomiumClient(feedHttpClient, decoder);

// for the handler API: the content decoder derived from the type of your handler
FeedContentDecoder<MyEvent> contentDecoder = JacksonFeedContentDecoder.of(myHandler, jsonMapper);
```

`JacksonFeedPageDecoder` creates and configures its **own** `JsonMapper`: it decodes only the
envelope and stays independent of the Jackson config your application uses for its own domain content.
`JacksonFeedContentDecoder`, by contrast, does take the `JsonMapper` from you — the entry content *is*
domain content.
