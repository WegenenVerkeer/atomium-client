package be.wegenenverkeer.atomium.client.springboot.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone demo application for {@code atomium-client-spring-boot-4}. Shows that a Spring Boot application can
 * use the generic lib with only the narrow seam: the one mandatory bean is {@link DemoFeedRestClientBuilders}.
 * Intended for local experimentation only: just start it (postgres via docker-compose, the in-memory
 * {@link DemoFeedEndpoint} as source feed). The admin endpoints are secured with a simple in-memory user (see
 * {@link SecurityConfig}).
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
