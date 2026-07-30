/**
 * Spring adapter for {@code atomium-client-core}: a
 * {@link be.wegenenverkeer.atomium.client.port.FeedHttpClient} on top of the Spring
 * {@link org.springframework.web.client.RestClient}.
 *
 * <p>The application supplies a <em>configured</em> {@code RestClient} (base url, logging, retries,
 * timeouts, authentication, …); this adapter adds nothing beyond translating a relative link + optional
 * {@code If-None-Match} into a GET, and returning status/headers/body raw (so that
 * {@code 304}/{@code 410}/… are not lost as an exception).
 */
@NullMarked
package be.wegenenverkeer.atomium.client.spring;

import org.jspecify.annotations.NullMarked;
