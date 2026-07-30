package be.wegenenverkeer.atomium.client.jackson;

import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPage;
import be.wegenenverkeer.atomium.client.protocol.Link;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonFeedPageDecoderTest {

    private final JacksonFeedPageDecoder decoder = new JacksonFeedPageDecoder();

    /**
     * Decodes {@code feedpages/1.json} and validates each field separately, so that a breakage immediately
     * points to the field involved instead of "big object not equal to big object".
     */
    @Test
    void decodesEveryField() {
        FeedPage page = decoder.readFeedPage(resource("1.json"));

        // --- metadata ---
        assertThat(page.metadata().id()).isEqualTo("test feed");
        assertThat(page.metadata().base()).isEqualTo("/feed");
        assertThat(page.metadata().title()).isEqualTo("test feed");
        assertThat(page.metadata().updated()).isEqualTo(OffsetDateTime.parse("2026-01-31T12:06:00+01:00"));

        // --- generator ---
        assertThat(page.metadata().generator().text()).isEqualTo("test");
        assertThat(page.metadata().generator().uri()).isEqualTo("urn:test");
        assertThat(page.metadata().generator().version()).isEqualTo("1.0");

        // --- links (each rel -> href) ---
        assertThat(page.metadata().links()).hasSize(4);
        assertLink(page.metadata().links().get(0), "self", "/1");
        assertLink(page.metadata().links().get(1), "last", "/0");
        assertLink(page.metadata().links().get(2), "next", "/0");
        assertLink(page.metadata().links().get(3), "previous", "/2");

        // --- entries (protocol order = youngest first) ---
        assertThat(page.entries()).hasSize(3);

        assertEntry(page.entries().get(0), "id-006", "2026-01-31T12:06:00+01:00", null, "fieldValue-6");
        assertThat(page.entries().get(0).links()).isEmpty();

        assertEntry(page.entries().get(1), "id-005", "2026-01-31T12:05:00+01:00", null, "fieldValue-5");
        assertThat(page.entries().get(1).links()).isEmpty();

        assertEntry(page.entries().get(2), "id-004", "2026-01-31T12:04:00+01:00", "A_TYPE", "fieldValue-4");
        assertThat(page.entries().get(2).links()).hasSize(2);
        assertLink(page.entries().get(2).links().get(0), "self", "/entries/id-004");
        assertLink(page.entries().get(2).links().get(1), "detail", "/entries/id-004/detail");
    }

    @Test
    void minimalPage() {
        FeedPage page = decoder.readFeedPage(resource("minimal.json"));

        // --- metadata ---
        assertThat(page.metadata().id()).isEqualTo("test feed");
        assertThat(page.metadata().base()).isEqualTo("/feed");
        assertThat(page.metadata().title()).isEqualTo("test feed");
        assertThat(page.metadata().updated()).isEqualTo(OffsetDateTime.parse("2026-01-31T12:06:00+01:00"));

        // --- generator ---
        assertThat(page.metadata().generator().text()).isEqualTo("test");
        assertThat(page.metadata().generator().uri()).isEqualTo("urn:test");
        assertThat(page.metadata().generator().version()).isEqualTo("1.0");

        // --- links (each rel -> href) ---
        assertThat(page.metadata().links()).hasSize(2);
        assertLink(page.metadata().links().get(0), "self", "/0");
        assertLink(page.metadata().links().get(1), "last", "/0");

        assertThat(page.entries()).isEmpty();
    }

    private static void assertEntry(AtomiumEntry entry, String id, String updated, String contentType, String contentValueFieldValue) {
        assertThat(entry.id()).isEqualTo(id);
        assertThat(entry.updated()).isEqualTo(OffsetDateTime.parse(updated));
        assertThat(entry.content().type()).isEqualTo(contentType);
        // content.value stays raw JSON
        assertThat(entry.content().value()).isEqualTo("{\"aField\":\"%s\"}".formatted(contentValueFieldValue));
    }

    private void assertLink(Link link, String rel, String href) {
        assertThat(link.rel()).isEqualTo(rel);
        assertThat(link.href()).isEqualTo(href);
    }

    private static String resource(String file) {
        String path = "/feedpages/" + file;
        try (InputStream in = JacksonFeedPageDecoderTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Minimal, valid page envelope around the given entries JSON.
     */
    private static String pageWith(String entriesJson) {
        return """
                {
                  "id": "f", "base": "/feed", "title": "t",
                  "generator": {"text": "g", "uri": "u", "version": "1"},
                  "updated": "2026-01-31T12:00:00+01:00",
                  "links": [{"rel": "self", "href": "/1"}, {"rel": "last", "href": "/0"}],
                  "entries": [%s]
                }""".formatted(entriesJson);
    }

    @Nested
    class FailureCases {

        @Test
        void rejectsSyntacticallyInvalidJson() {
            assertThatThrownBy(() -> decoder.readFeedPage("{ this is not valid json"))
                    .isInstanceOf(AtomiumInvalidPageException.class);
        }

        @Test
        void rejectsEmptyInput() {
            assertThatThrownBy(() -> decoder.readFeedPage(""))
                    .isInstanceOf(AtomiumInvalidPageException.class);
        }

        @Test
        void rejectsJsonThatIsNotAnObject() {
            assertThatThrownBy(() -> decoder.readFeedPage("[]"))
                    .isInstanceOf(AtomiumInvalidPageException.class);
        }

        @Test
        void rejectsMissingRequiredField() {
            String json = """
                    {"base": "/feed", "title": "t",
                     "generator": {"text": "g", "uri": "u", "version": "1"},
                     "updated": "2026-01-31T12:00:00+01:00", "links": [], "entries": []}""";
            assertThatThrownBy(() -> decoder.readFeedPage(json))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("id");
        }

        @Test
        void rejectsNullRequiredField() {
            String json = """
                    {"id": null, "base": "/feed", "title": "t",
                     "generator": {"text": "g", "uri": "u", "version": "1"},
                     "updated": "2026-01-31T12:00:00+01:00", "links": [], "entries": []}""";
            assertThatThrownBy(() -> decoder.readFeedPage(json))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("id");
        }

        @Test
        void rejectsMissingGenerator() {
            String json = """
                    {"id": "f", "base": "/feed", "title": "t",
                     "updated": "2026-01-31T12:00:00+01:00", "links": [], "entries": []}""";
            assertThatThrownBy(() -> decoder.readFeedPage(json))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("generator");
        }

        @Test
        void rejectsNullGenerator() {
            String json = """
                    {"id": "f", "base": "/feed", "title": "t", "generator": null,
                     "updated": "2026-01-31T12:00:00+01:00", "links": [], "entries": []}""";
            assertThatThrownBy(() -> decoder.readFeedPage(json))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("generator");
        }

        @Test
        void rejectsInvalidTimestamp() {
            String json = """
                    {"id": "f", "base": "/feed", "title": "t",
                     "generator": {"text": "g", "uri": "u", "version": "1"},
                     "updated": "not-a-date", "links": [], "entries": []}""";
            assertThatThrownBy(() -> decoder.readFeedPage(json))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("updated");
        }

        @Test
        void rejectsUnknownEntryType() {
            String entry = """
                    {"_type": "atom-pub", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            assertThatThrownBy(() -> decoder.readFeedPage(pageWith(entry)))
                    .isInstanceOf(AtomiumInvalidPageException.class)
                    .hasMessageContaining("atom-pub");
        }
    }

    @Nested
    class OptionalFields {

        @Test
        void acceptsEntryWithoutType() {
            String entry = """
                    {"id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            assertThat(decoder.readFeedPage(pageWith(entry)).entries())
                    .singleElement()
                    .satisfies(e -> assertThat(e.id()).isEqualTo("id-1"));
        }

        @Test
        void acceptsEmptyEntryType() {
            String entry = """
                    {"_type": "", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            assertThat(decoder.readFeedPage(pageWith(entry)).entries()).hasSize(1);
        }

        @Test
        void acceptsNullEntryType() {
            String entry = """
                    {"_type": null, "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            assertThat(decoder.readFeedPage(pageWith(entry)).entries()).hasSize(1);
        }

        @Test
        void returnsEmptyListWhenEntriesFieldMissing() {
            String json = """
                    {"id": "f", "base": "/feed", "title": "t",
                     "generator": {"text": "g", "uri": "u", "version": "1"},
                     "updated": "2026-01-31T12:00:00+01:00", "links": [{"rel": "self", "href": "/1"}]}""";
            assertThat(decoder.readFeedPage(json).entries()).isEmpty();
        }

        @Test
        void acceptsEntryWithoutContent() {
            String entry = """
                    {"_type": "atom", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00"}""";
            AtomiumEntry entry1 = decoder.readFeedPage(pageWith(entry)).entries().getFirst();
            assertThat(entry1.content().type()).isNull();
            assertThat(entry1.content().value()).isEmpty();
        }

        @Test
        void acceptsNullContent() {
            String entry = """
                    {"_type": "atom", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": null}""";
            AtomiumEntry entry1 = decoder.readFeedPage(pageWith(entry)).entries().getFirst();
            assertThat(entry1.content().type()).isNull();
            assertThat(entry1.content().value()).isEmpty();
        }

        @Test
        void acceptsContentWithoutValue() {
            String entry = """
                    {"_type": "atom", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"type": "T"}}""";
            AtomiumEntry entry1 = decoder.readFeedPage(pageWith(entry)).entries().getFirst();
            assertThat(entry1.content().type()).isEqualTo("T");
            assertThat(entry1.content().value()).isEmpty();
        }

        @Test
        void acceptsContentWithoutType() {
            String entry = """
                    {"_type": "atom", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            AtomiumEntry entry1 = decoder.readFeedPage(pageWith(entry)).entries().getFirst();
            assertThat(entry1.content().type()).isNull();
            assertThat(entry1.content().value()).isEqualTo("{\"x\":1}");
        }

        @Test
        void returnsEmptyLinkListForEntryWithoutLinks() {
            String entry = """
                    {"_type": "atom", "id": "id-1", "updated": "2026-01-31T12:00:00+01:00", "content": {"value": {"x": 1}}}""";
            assertThat(decoder.readFeedPage(pageWith(entry)).entries().getFirst().links()).isEmpty();
        }
    }
}
