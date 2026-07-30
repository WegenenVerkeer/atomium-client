package be.wegenenverkeer.atomium.client.fetch;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds small, readable feed page JSON for the tests. Deliberately simple: small pages, non-random
 * ids such as {@code id-001}, and content with one meaningful field such as {@code {"aField":"fieldValue-1"}}.
 *
 * <p>Entries are added <strong>youngest-first</strong>, just as they appear in the real JSON.
 */
final class FeedFixture {

    private FeedFixture() {
    }

    static Page page(String self) {
        return new Page(self);
    }

    static final class Page {
        private final String self;
        private @Nullable String last;
        private @Nullable String previous;
        private @Nullable String next;
        private String updated = "2026-01-31T12:00:00+01:00";
        private final List<String> entries = new ArrayList<>(); // youngest-first

        private Page(String self) {
            this.self = self;
        }

        /** The {@code last} link: the oldest page. */
        Page oldest(String href) {
            this.last = href;
            return this;
        }

        /** The {@code previous} link: the next younger page. Present ⇒ the page is complete. */
        Page younger(String href) {
            this.previous = href;
            return this;
        }

        /** The {@code next} link: the next older page. */
        Page older(String href) {
            this.next = href;
            return this;
        }

        Page updated(String updated) {
            this.updated = updated;
            return this;
        }

        /** Add an entry (youngest-first) with content {@code {"aField": fieldValue}}. */
        Page entry(String id, String updated, String fieldValue) {
            entries.add("""
                    {"_type":"atom","id":"%s","updated":"%s","content":{"type":"","value":{"aField":"%s"}},"links":[]}"""
                    .formatted(id, updated, fieldValue));
            return this;
        }

        /** Add an entry with an explicit (possibly invalid) {@code _type}. */
        Page entryWithType(String type, String id, String updated, String fieldValue) {
            entries.add("""
                    {"_type":"%s","id":"%s","updated":"%s","content":{"type":"","value":{"aField":"%s"}},"links":[]}"""
                    .formatted(type, id, updated, fieldValue));
            return this;
        }

        String json() {
            List<String> links = new ArrayList<>();
            links.add(link("self", self));
            if (last != null) {
                links.add(link("last", last));
            }
            if (next != null) {
                links.add(link("next", next));
            }
            if (previous != null) {
                links.add(link("previous", previous));
            }
            return """
                    {"id":"test feed","base":"/feed","title":"test feed",\
                    "generator":{"text":"test","uri":"urn:test","version":"1.0"},\
                    "updated":"%s",\
                    "links":[%s],\
                    "entries":[%s]}""".formatted(updated, String.join(",", links), String.join(",", entries));
        }

        private static String link(String rel, String href) {
            return "{\"rel\":\"%s\",\"href\":\"%s\"}".formatted(rel, href);
        }
    }
}
