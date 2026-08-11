package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import be.wegenenverkeer.atomium.client.handler.FeedRunner;
import be.wegenenverkeer.atomium.client.handler.Feeds;
import be.wegenenverkeer.atomium.client.springboot.FooAppFeedEntry;
import be.wegenenverkeer.atomium.client.springboot.FooAppFeedHandler;
import be.wegenenverkeer.atomium.client.springboot.TestApp;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RestTest for the {@code AtomiumAdminEndpoint}: validates the full functionality over a real HTTP server
 * (RANDOM_PORT), with a WireMock feed and a postgres container. Security via HTTP Basic with two in-memory users;
 * the authorization really goes through the (mandatory) {@code AtomiumAdminAuthorization} bean from the
 * {@link SecurityConfig}.
 *
 * <p>There are four registered test feeds: {@code foo-app} (= {@link #FEED}), {@code foo-app-batch},
 * {@code foo-app-batch-cap} and {@code foo-app-empty-page}.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "atomium.admin.enabled=true")
@EnableWireMock(@ConfigureWireMock(name = "feed", portProperties = "wiremock.feed.port"))
@ActiveProfiles({"test", "wiremock"})
@Import(AtomiumAdminRestTest.SecurityConfig.class)
@Tag("ittest")
class AtomiumAdminRestTest {

    private static final String FEED = "foo-app";
    private static final String OTHER_FEED = "foo-app-empty-page";
    private static final String BATCH_FEED = "foo-app-batch";
    private static final String BATCH_CAP_FEED = "foo-app-batch-cap";
    private static final String SYSTEM_ADMIN = "sysadmin";
    private static final String REGULAR_USER = "regular-user";
    private static final String PASSWORD = "pw";

    /** Security as a real app would provide it: HTTP Basic filter chain + the AtomiumAdminAuthorization bean. */
    @TestConfiguration
    @EnableWebSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(spec -> spec.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .build();
        }

        @Bean
        AtomiumAdminAuthorization atomiumAdminAuthorization() {
            return new HasAuthorityAtomiumAdminAuthorization("SYSTEM-ADMIN");
        }

        @Bean
        UserDetailsService users() {
            // .authorities(...) instead of .roles(...) → the authority is exactly 'system-admin' (no ROLE_ prefix)
            return new InMemoryUserDetailsManager(
                    User.withUsername(SYSTEM_ADMIN).password("{noop}" + PASSWORD).authorities("system-admin").build(),
                    User.withUsername(REGULAR_USER).password("{noop}" + PASSWORD).authorities("application-admin").build());
        }
    }

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static {
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @InjectWireMock("feed")
    private WireMockServer wiremock;

    @LocalServerPort
    private int port;

    @Autowired
    private Feeds feeds;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FooAppFeedHandler handler; // the handler behind FEED (foo-app)

    private RestTestClient admin;
    private RestTestClient regular;

    @BeforeEach
    void setup() {
        wiremock.resetAll();
        // reset to inactive (via the runner, without the service validation) and wait until any run of a
        // previous test has completed (activate now immediately triggers a run) before we empty the table
        feeds.get(FEED).runner().deactivate();
        feeds.get(OTHER_FEED).runner().deactivate();
        awaitUntil(() -> !feeds.get(FEED).runner().isRunning() && !feeds.get(OTHER_FEED).runner().isRunning());
        // a previous test may have left a feed in backoff or poll wait time; clear that so every test starts clean
        feeds.get(FEED).runner().scheduleNextRunNow();
        feeds.get(OTHER_FEED).runner().scheduleNextRunNow();
        jdbcClient.sql("TRUNCATE atomium_feed_pointer_v1").update();
        admin = client(SYSTEM_ADMIN);
        regular = client(REGULAR_USER);
    }

    @Nested
    class Happy {

        @Test
        void feedPointers() {
            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/5", "id-042")).exchange().expectStatus().isOk();

            List<FeedPointerDto> dtos = getOk("/rest/atomium/feed-pointer",
                    new ParameterizedTypeReference<>() {
                    });

            // the next run resumes just after id-042 on /5 (next-fetch derived from the lastEvent)
            assertThat(dtos).contains(
                    new FeedPointerDto(FEED, new FeedPointerDto.Position("/5", "id-042", "/5", null)),
                    new FeedPointerDto(OTHER_FEED, null)); // the other feed has no pointer yet
        }

        @Test
        void feedPointer() {
            assertThat(getOk("/rest/atomium/feed/{id}/feed-pointer", FeedPointerDto.class, FEED))
                    .isEqualTo(new FeedPointerDto(FEED, null)); // no pointer yet

            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/9", "id-100")).exchange().expectStatus().isOk();

            assertThat(getOk("/rest/atomium/feed/{id}/feed-pointer", FeedPointerDto.class, FEED))
                    .isEqualTo(new FeedPointerDto(FEED, new FeedPointerDto.Position("/9", "id-100", "/9", null)));
        }

        @Test
        void status() {
            List<FeedStatusDto> dtos = getOk("/rest/atomium/status",
                    new ParameterizedTypeReference<>() {
                    });

            // sorted by feedId; all feeds inactive and without backoff
            assertThat(dtos).containsExactly(
                    new FeedStatusDto(FEED, false, false, 0, null),
                    new FeedStatusDto(BATCH_FEED, false, false, 0, null),
                    new FeedStatusDto(BATCH_CAP_FEED, false, false, 0, null),
                    new FeedStatusDto(OTHER_FEED, false, false, 0, null));
        }

        @Test
        void feedStatus() {
            assertThat(getOk("/rest/atomium/feed/{id}/status", FeedStatusDto.class, FEED))
                    .isEqualTo(new FeedStatusDto(FEED, false, false, 0, null));

            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isOk();
            // activate immediately triggers a run (which fails here on the unstubbed WireMock); wait until it has completed
            awaitUntil(() -> !feeds.get(FEED).runner().isRunning());

            // the failed run sets the backoff state: active, not running, 1 failure, a next-attempt deadline
            FeedStatusDto status = getOk("/rest/atomium/feed/{id}/status", FeedStatusDto.class, FEED);
            assertThat(status.feedId()).isEqualTo(FEED);
            assertThat(status.active()).isTrue();
            assertThat(status.running()).isFalse();
            assertThat(status.consecutiveFailures()).isEqualTo(1);
            assertThat(status.nextRun()).isNotNull();
        }

        @Test
        void config() {
            List<Map<String, Object>> configs = getOk("/rest/atomium/config",
                    new ParameterizedTypeReference<>() {
                    });

            Map<String, Object> config = configOf(configs, FEED);
            assertFieldsOfTheFeedConfig(config);
            assertThat(configs).anySatisfy(c -> assertThat(c).containsEntry("feedId", OTHER_FEED));
        }

        @Test
        void feedConfig() {
            Map<String, Object> dto = getOk("/rest/atomium/feed/{id}/config",
                    new ParameterizedTypeReference<>() {
                    }, FEED);

            assertThat(dto).containsEntry("feedId", FEED);
            assertFieldsOfTheFeedConfig(dto);
        }

        @Test
        void setFeedPointerWithEventId() {
            // feed is inactive (see setup) → setting is allowed; the next run resumes just after id-042
            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/5", "id-042")).exchange().expectStatus().isOk();

            assertThat(getOk("/rest/atomium/feed/{id}/feed-pointer", FeedPointerDto.class, FEED))
                    .isEqualTo(new FeedPointerDto(FEED, new FeedPointerDto.Position("/5", "id-042", "/5", null)));
        }

        @Test
        void setFeedPointerWithoutEventId() {
            // without an eventId the next run reads the page from the beginning (no filter)
            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/5", null)).exchange().expectStatus().isOk();

            assertThat(getOk("/rest/atomium/feed/{id}/feed-pointer", FeedPointerDto.class, FEED))
                    .isEqualTo(new FeedPointerDto(FEED, new FeedPointerDto.Position(null, null, "/5", null)));
        }

        @Test
        void activate() {
            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isOk();

            assertThat(getOk("/rest/atomium/feed/{id}/status", FeedStatusDto.class, FEED).active()).isTrue();
        }

        @Test
        void deactivate() {
            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isOk();

            admin.put().uri("/rest/atomium/feed/{id}/deactivate", FEED).exchange().expectStatus().isOk();

            assertThat(getOk("/rest/atomium/feed/{id}/status", FeedStatusDto.class, FEED).active()).isFalse();
        }

        @Test
        void rawHeadPage() {
            wiremock.stubFor(get(urlPathEqualTo("/feed")).willReturn(okJson("{\"raw\":\"head\"}")));

            Map<String, Object> response = getOk("/rest/atomium/feed/{id}/feed",
                    new ParameterizedTypeReference<>() {
                    }, FEED);

            assertThat(response).containsEntry("status", 200);
            // body is nested JSON (not an escaped string)
            assertThat(response.get("body")).isEqualTo(Map.of("raw", "head"));
            assertThat(response.get("headers")).isInstanceOfSatisfying(Map.class,
                    headers -> assertThat(headers).containsKey("Content-Type"));
        }

        @Test
        void rawPage() {
            wiremock.stubFor(get(urlPathEqualTo("/feed/182")).willReturn(okJson("{\"raw\":\"182\"}")));

            Map<String, Object> response = getOk("/rest/atomium/feed/{id}/feed/{page}",
                    new ParameterizedTypeReference<>() {
                    }, FEED, "182");

            assertThat(response).containsEntry("status", 200);
            assertThat(response.get("body")).isEqualTo(Map.of("raw", "182"));
        }

        @Test
        void rawPageWithSlashInThePageLink() {
            // a page link with a slash (e.g. /0/100) — thanks to {*pageLink} no 404
            wiremock.stubFor(get(urlPathEqualTo("/feed/0/100")).willReturn(okJson("{\"raw\":\"0/100\"}")));

            Map<String, Object> response = getOk("/rest/atomium/feed/{id}/feed/0/100",
                    new ParameterizedTypeReference<>() {
                    }, FEED);

            assertThat(response).containsEntry("status", 200);
            assertThat(response.get("body")).isEqualTo(Map.of("raw", "0/100"));
        }

        @Test
        void pushEntry() {
            handler.reset();

            admin.post().uri("/rest/atomium/feed/{id}/push", FEED)
                    .body(new FooAppFeedEntry("pushed"))
                    .exchange().expectStatus().isOk();

            // the content is decoded and passed to the handler (via FeedPusher.pushEntry)
            assertThat(handler.invocations()).containsExactly("pushEntry(pushed)");
        }
    }

    @Nested
    class ValidationErrors {

        @Test
        void feedPointer_ifFeedDoesNotExist() {
            admin.get().uri("/rest/atomium/feed/{id}/feed-pointer", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void feedStatus_ifFeedDoesNotExist() {
            admin.get().uri("/rest/atomium/feed/{id}/status", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void feedConfig_ifFeedDoesNotExist() {
            admin.get().uri("/rest/atomium/feed/{id}/config", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void rawPage_ifFeedDoesNotExist() {
            admin.get().uri("/rest/atomium/feed/{id}/feed", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void activate_ifFeedDoesNotExist() {
            admin.put().uri("/rest/atomium/feed/{id}/activate", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void deactivate_ifFeedDoesNotExist() {
            admin.put().uri("/rest/atomium/feed/{id}/deactivate", "unknown").exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void setFeedPointer_ifFeedDoesNotExist() {
            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", "unknown")
                    .body(new SetFeedPointerCommand("/1", "id-1")).exchange().expectStatus().isNotFound();
        }

        @Test
        void setFeedPointer_withEmptyPageLink() {
            // an empty pageLink is a validation error → 400 (not 500)
            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("", null)).exchange().expectStatus().isBadRequest();
        }

        @Test
        void push_ifFeedDoesNotExist() {
            admin.post().uri("/rest/atomium/feed/{id}/push", "unknown")
                    .body(new FooAppFeedEntry("x")).exchange().expectStatus().isNotFound();
        }

        @Test
        void push_ifHandlerDoesNotSupportPush() {
            // the handler behind OTHER_FEED (foo-app-empty-page) does not implement FeedPusher →
            // UnsupportedOperationException → 400 instead of a hidden NPE.
            admin.post().uri("/rest/atomium/feed/{id}/push", OTHER_FEED)
                    .body(new FooAppFeedEntry("x")).exchange().expectStatus().isBadRequest();
        }

        @Test
        void activate_ifAlreadyActive() {
            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isOk();

            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isBadRequest();
        }

        @Test
        void deactivate_ifAlreadyInactive() {
            // feed is inactive (setup) → deactivating is not allowed
            admin.put().uri("/rest/atomium/feed/{id}/deactivate", FEED).exchange().expectStatus().isBadRequest();
        }

        @Test
        void setFeedPointer_ifFeedActive() {
            admin.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isOk();

            admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/1", "id-1")).exchange().expectStatus().isBadRequest();
        }

        @Test
        void setFeedPointer_ifFeedRunning() {
            // head with a delay so the run stays busy (running) while we try to set the pointer
            wiremock.stubFor(get(urlPathEqualTo("/feed"))
                    .willReturn(okJson(resource("2-v1.json")).withFixedDelay(1500)));

            FeedRunner runner = feeds.get(FEED).runner();
            runner.activate();
            assertThat(runner.tryToStart()).isTrue(); // running=true, the run blocks in the head fetch
            // wait until the run is actually inside the fetch before deactivating: a run that has not started yet
            // sees active=false and immediately returns without doing anything (the deactivateAndAwait guarantee) — then this tests nothing
            awaitUntil(() -> !wiremock.findAll(getRequestedFor(urlPathEqualTo("/feed"))).isEmpty());
            runner.deactivate();                             // active=false, but running is still true
            try {
                assertThat(runner.isRunning()).isTrue();
                admin.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                        .body(new SetFeedPointerCommand("/1", "id-1")).exchange().expectStatus().isBadRequest();
            } finally {
                awaitUntil(() -> !runner.isRunning()); // let the run finish before the next test
            }
        }
    }

    @Nested
    class IfNotSystemAdminThen403 {

        @Test
        void feedPointers() {
            regular.get().uri("/rest/atomium/feed-pointer").exchange().expectStatus().isForbidden();
        }

        @Test
        void feedPointer() {
            regular.get().uri("/rest/atomium/feed/{id}/feed-pointer", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void status() {
            regular.get().uri("/rest/atomium/status").exchange().expectStatus().isForbidden();
        }

        @Test
        void feedStatus() {
            regular.get().uri("/rest/atomium/feed/{id}/status", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void config() {
            regular.get().uri("/rest/atomium/config").exchange().expectStatus().isForbidden();
        }

        @Test
        void feedConfig() {
            regular.get().uri("/rest/atomium/feed/{id}/config", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void rawHeadPage() {
            regular.get().uri("/rest/atomium/feed/{id}/feed", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void rawPage() {
            regular.get().uri("/rest/atomium/feed/{id}/feed/{page}", FEED, "0").exchange().expectStatus().isForbidden();
        }

        @Test
        void setFeedPointer() {
            regular.put().uri("/rest/atomium/feed/{id}/feed-pointer", FEED)
                    .body(new SetFeedPointerCommand("/1", "id-1")).exchange().expectStatus().isForbidden();
        }

        @Test
        void activate() {
            regular.put().uri("/rest/atomium/feed/{id}/activate", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void deactivate() {
            regular.put().uri("/rest/atomium/feed/{id}/deactivate", FEED).exchange().expectStatus().isForbidden();
        }

        @Test
        void push() {
            regular.post().uri("/rest/atomium/feed/{id}/push", FEED)
                    .body(new FooAppFeedEntry("x")).exchange().expectStatus().isForbidden();
        }
    }

    // --- helpers ---

    private RestTestClient client(String user) {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic).build();
    }

    private <T> T getOk(String uri, ParameterizedTypeReference<T> type, Object... uriVars) {
        return admin.get().uri(uri, uriVars).exchange().expectStatus().isOk()
                .expectBody(type).returnResult().getResponseBody();
    }

    private <T> T getOk(String uri, Class<T> type, Object... uriVars) {
        return admin.get().uri(uri, uriVars).exchange().expectStatus().isOk()
                .expectBody(type).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(List<Map<String, Object>> configs, String feedId) {
        return configs.stream()
                .filter(c -> feedId.equals(c.get("feedId")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void assertFieldsOfTheFeedConfig(Map<String, Object> feedConfigDto) {
        Map<String, Object> config = (Map<String, Object>) feedConfigDto.get("config");
        assertThat(config).containsEntry("activeOnStartup", false);
        assertThat(config).containsEntry("queryInterval", "PT1H");
        assertThat((Map<String, Object>) config.get("initialFeedPointer")).containsEntry("type", "OLDEST");
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("condition not reached within the timeout");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = AtomiumAdminRestTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
