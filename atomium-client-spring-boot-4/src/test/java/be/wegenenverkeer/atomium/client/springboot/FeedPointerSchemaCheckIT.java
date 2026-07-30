package be.wegenenverkeer.atomium.client.springboot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * IT for the startup schema check of {@link JdbcFeedPointerRepository}: if the table
 * {@code atomium_feed_pointer_v1} is missing, {@link JdbcFeedPointerRepository#verifySchema()} fails clearly; after
 * the app provides the table (here via Flyway) it succeeds.
 */
@Tag("ittest")
class FeedPointerSchemaCheckIT {

    @Test
    void failsWhenTheTableIsMissingAndSucceedsAfterMigration() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var repository = new JdbcFeedPointerRepository(JdbcClient.create(dataSource));

            // the table does not exist yet -> clear startup failure that names the table
            assertThatIllegalStateException().isThrownBy(repository::verifySchema)
                    .withMessageContaining("atomium_feed_pointer_v1");

            // after the app provides the table -> no more failure
            Flyway.configure().dataSource(dataSource).load().migrate();
            repository.verifySchema();
        }
    }
}
