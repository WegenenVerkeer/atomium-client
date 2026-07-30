package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPointerRepository;

import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FakeFeedHttpClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.JacksonFeedPageDecoder;
import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.InitialFeedPointer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties.InitialFeedPointer.Type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests the translation of the {@code initialFeedPointer} config to a start-position strategy and the
 * fail-fast for a brand-new feed without config and without persisted pointer.
 */
class FeedFactoryTest {

    // a real AtomiumClient over the shared in-memory source feed (core test jar)
    private final AtomiumClient atomiumClient = new AtomiumClient(new FakeFeedHttpClient().head("/2")
            .page("/0", resource("0.json"))
            .page("/1", resource("1.json"))
            .page("/2", resource("2-v1.json")), new JacksonFeedPageDecoder());

    @Test
    void oldestDelegatesToTheClient() {
        var supplier = FeedFactory.determineInitialFeedPointer(
                "feed", new InitialFeedPointer(Type.OLDEST, null), atomiumClient, repoWith(null));

        assertThat(supplier.get()).isEqualTo(atomiumClient.pointerToOldest());
    }

    @Test
    void nowDelegatesToTheClient() {
        var supplier = FeedFactory.determineInitialFeedPointer(
                "feed", new InitialFeedPointer(Type.NOW, null), atomiumClient, repoWith(null));

        assertThat(supplier.get()).isEqualTo(atomiumClient.pointerFromNow());
    }

    @Test
    void anExplicitPointerUsesThePageLink() {
        var supplier = FeedFactory.determineInitialFeedPointer(
                "feed", new InitialFeedPointer(Type.POINTER, "/182"), atomiumClient, repoWith(null));

        assertThat(supplier.get()).isEqualTo(new FeedPointer("/182"));
    }

    @Test
    void noConfigAndAnEmptyRepoFailsImmediately() {
        // fail-fast: a brand-new feed without config and without persisted pointer
        assertThatIllegalStateException().isThrownBy(() -> FeedFactory.determineInitialFeedPointer(
                        "feed", null, atomiumClient, repoWith(null)))
                .withMessageContaining("initialFeedPointer");
    }

    @Test
    void noConfigButAPersistedPointerIsOk() {
        // the repo has a pointer -> no fail-fast; in practice the supplier is never consulted
        var supplier = FeedFactory.determineInitialFeedPointer(
                "feed", null, atomiumClient, repoWith(new FeedPointer("/42")));

        // if it is consulted anyway, it fails clearly instead of silently starting in the wrong place
        assertThatIllegalStateException().isThrownBy(supplier::get);
    }

    /** Loads a feed page JSON from the shared {@code feedpages} fixtures (core test jar). */
    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = FeedFactoryTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FeedPointerRepository repoWith(@Nullable FeedPointer stored) {
        return new FeedPointerRepository() {
            @Override
            public Optional<FeedPointer> find(String feedId) {
                return Optional.ofNullable(stored);
            }

            @Override
            public void save(String feedId, FeedPointer feedPointer) {
            }
        };
    }
}
