package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared base for the framework integration tests: a {@link TestApp} context with a WireMock feed and a
 * postgres container (testcontainers) for the feedPointer table. Subclasses choose their own executor (via a
 * {@link FeedCustomizer} with an inline executor, or the default per-feed thread) and optionally add their own
 * {@code @TestPropertySource} config.
 */
@SpringBootTest(classes = TestApp.class)
@EnableWireMock(@ConfigureWireMock(name = "feed", portProperties = "wiremock.feed.port"))
@ActiveProfiles({"test", "wiremock"})
@Tag("ittest")
public abstract class AbstractAtomiumFeedIT {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static {
        postgres.start();
        // create the table (as a real app would do with Flyway) before the Spring context, so that the
        // startup schema check of FeedPointerRepository finds it. Flyway writes to flyway_schema_history,
        // so any Flyway autoconfig in the context sees it as already applied.
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
    protected WireMockServer wiremock;

    protected static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = AbstractAtomiumFeedIT.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
