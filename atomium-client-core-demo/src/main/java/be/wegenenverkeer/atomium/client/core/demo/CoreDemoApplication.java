package be.wegenenverkeer.atomium.client.core.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Standalone demo application for {@code atomium-client-core}: uses both core APIs <em>without</em> the
 * {@code atomium-client-spring-boot-4} module — the fetch API directly ({@code /rest/demo/fetch}), and the
 * handler API by assembling a {@code Feed} per feed from the building blocks ({@code simple} minimal,
 * {@code simple-processing} two-phase, {@code full-monty} with all the bells and whistles). Spring Boot only serves as application setup here; the
 * assembly is exactly what any other stack would write as well. Intended for local experimentation only: just
 * start it (H2 in-memory, its own {@link DemoFeedEndpoint} as source feed — no Docker required).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreDemoApplication.class, args);
    }
}
