# atomium-client-restclient

> Part of the [`atomium-client` suite](../README.md) — see there for the overview and the integration paths.

Spring adapter for [`atomium-client-core`](../atomium-client-core/README.md): an implementation of the
port `FeedHttpClient` on top of Spring's `RestClient`.

The application supplies a **configured** `RestClient` (base URL, logging, retries, timeouts,
authentication, …); this adapter adds nothing beyond translating a relative page link
+ optional `If-None-Match` into a GET, and returning status/headers/body raw — so that a `304`,
`410` or `5xx` is not lost as an exception but handled by the core.

## When do you (not) need this module?

- **You do**, if you wire the `AtomiumClient` by hand with a Spring `RestClient`, without the Spring
  Boot auto-configuration.
- **You don't**, if you use [`atomium-client-spring-boot-4`](../atomium-client-spring-boot-4/README.md): it
  brings this module in transitively and builds the HTTP client from your `FeedRestClientBuilders` seam.

## Usage

```java
RestClient restClient = RestClient.builder().baseUrl("https://source/call-service/feed").build();
FeedHttpClient httpClient = new SpringFeedHttpClient(restClient);
var client = new AtomiumClient(httpClient, feedPageDecoder);
```

Query parameters that must travel with **every** fetch (the head as well as every page) — for feed
servers whose behavior is steered per request, e.g. a (multi-valued) server-side filter — go in the
second constructor argument. They are added to any query parameters the page href itself carries:

```java
FeedHttpClient httpClient = new SpringFeedHttpClient(restClient,
        Map.of("ignore-readmodels", List.of("true"), "type", List.of("x", "y")));
```
