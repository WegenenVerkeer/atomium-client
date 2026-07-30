package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;

import be.wegenenverkeer.atomium.client.fetch.EventCoordinate;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchCoordinate;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@link JdbcFeedPointerRepository} against a real postgres (testcontainers). The table is created via
 * Flyway from {@code db/migration} (the schema a real application would provide itself).
 */
@Tag("ittest")
class FeedPointerRepositoryIT {

    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static FeedPointerRepository repository;

    @BeforeAll
    static void setup() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        repository = new JdbcFeedPointerRepository(JdbcClient.create(dataSource));
    }

    @Test
    void savesAndReadsBack() {
        assertThat(repository.find("foo-app.new")).isEmpty();

        repository.save("foo-app.new", onPage("/2", "id-008", "etag-v1"));

        assertThat(repository.find("foo-app.new")).contains(onPage("/2", "id-008", "etag-v1"));
    }

    @Test
    void storesNullFieldsCorrectly() {
        repository.save("foo-app.without-etag", new FeedPointer("/0"));

        assertThat(repository.find("foo-app.without-etag")).contains(new FeedPointer("/0"));
    }

    @Test
    void upsertOverwritesExistingRow() {
        repository.save("foo-app.upsert", new FeedPointer("/0"));
        repository.save("foo-app.upsert", onPage("/3", "id-100", "etag-x"));

        assertThat(repository.find("foo-app.upsert")).contains(onPage("/3", "id-100", "etag-x"));
    }

    @Test
    void feedsAreIndependent() {
        repository.save("feed-a", new FeedPointer("/1"));
        repository.save("feed-b", new FeedPointer("/2"));

        assertThat(repository.find("feed-a")).contains(new FeedPointer("/1"));
        assertThat(repository.find("feed-b")).contains(new FeedPointer("/2"));
    }

    /** Pointer that continues reading within the SAME page: the last processed event sits on the fetch page (filter == id). */
    private static FeedPointer onPage(String page, @Nullable String lastEventId, @Nullable String etag) {
        EventCoordinate lastEvent = lastEventId == null ? null : new EventCoordinate(page, lastEventId);
        return new FeedPointer(lastEvent, new FetchCoordinate(page, lastEventId, etag));
    }
}
