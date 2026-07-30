package be.wegenenverkeer.atomium.client.springboot.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An <em>in-memory</em> Atomium feed that makes the demo standalone (no external feed server needed). Keeps a
 * growing list of entries and serves them as paginated Atomium pages with the proper
 * {@code self}/{@code last}/{@code previous}/{@code next} links, in the same JSON format as a real feed.
 *
 * <p><b>Growth:</b> every time the <em>head</em> (youngest page) is requested — so on every poll by the
 * consumer — one entry is added. That way the feed produces new events by itself while you consume it.
 * Older (complete) pages are immutable. {@code id} and {@code aField} are derived from the index in the list,
 * {@code updated} is the time the entry was added.
 *
 * <p>Deliberately somewhat <em>lenient</em> for a demo: a requested page beyond the head (e.g. a stale pointer after a
 * restart, as the list then starts out empty again) is mapped onto the head, so the consumer re-syncs by itself.
 */
// identical copy of DemoFeedEndpoint in atomium-client-core-demo (the demos are deliberately standalone) — change them together
@RestController
@RequestMapping("/demo-feed")
public class DemoFeedEndpoint {

    /** Number of entries per page (kept small so the pagination becomes visible quickly). */
    private static final int PAGE_SIZE = 3;

    private final List<Entry> entries = new ArrayList<>();

    private record Entry(String id, String updated, String fieldValue) {
    }

    /** The head (youngest page). The base url without page suffix; every poll adds an entry. */
    @GetMapping
    public synchronized Map<String, Object> head() {
        addEntry();
        return page(headIndex());
    }

    /** A specific page. If it is the head, the poll adds an entry; beyond the head → map onto the head. */
    @GetMapping("/{pageNumber}")
    public synchronized Map<String, Object> pageFor(@PathVariable("pageNumber") int pageNumber) {
        int head = headIndex();
        int requested = (pageNumber < 0 || pageNumber > head) ? head : pageNumber;
        if (requested == head) {
            addEntry();
        }
        return page(requested);
    }

    private void addEntry() {
        int n = entries.size() + 1;
        String updated = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        entries.add(new Entry("id-%03d".formatted(n), updated, "fieldValue-" + n));
    }

    /** The index of the youngest (head) page; 0 for an empty feed. */
    private int headIndex() {
        return entries.isEmpty() ? 0 : (entries.size() - 1) / PAGE_SIZE;
    }

    private Map<String, Object> page(int pageNumber) {
        int head = headIndex();
        int from = pageNumber * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        List<Entry> pageEntries = from < entries.size() ? new ArrayList<>(entries.subList(from, to)) : List.of();

        // entries youngest first in the JSON (like a real Atomium feed)
        List<Map<String, Object>> entryJsons = new ArrayList<>();
        for (int i = pageEntries.size() - 1; i >= 0; i--) {
            entryJsons.add(entryJson(pageEntries.get(i)));
        }

        List<Map<String, String>> links = new ArrayList<>();
        links.add(link("self", "/" + pageNumber));
        links.add(link("last", "/0"));                                  // the oldest page
        if (pageNumber < head) {
            links.add(link("previous", "/" + (pageNumber + 1)));          // a younger page exists → this one is complete
        }
        if (pageNumber > 0) {
            links.add(link("next", "/" + (pageNumber - 1)));              // an older page
        }

        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("text", "demo");
        generator.put("uri", "urn:demo");
        generator.put("version", "1.0");

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", "demo feed");
        page.put("base", "/demo-feed");
        page.put("title", "demo feed");
        page.put("generator", generator);
        page.put("updated", pageEntries.isEmpty()
                ? OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
                : pageEntries.get(pageEntries.size() - 1).updated());
        page.put("links", links);
        page.put("entries", entryJsons);
        return page;
    }

    private static Map<String, Object> entryJson(Entry entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("aField", entry.fieldValue());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "");
        content.put("value", value);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("_type", "atom");
        json.put("id", entry.id());
        json.put("updated", entry.updated());
        json.put("content", content);
        json.put("links", List.of());
        return json;
    }

    private static Map<String, String> link(String rel, String href) {
        Map<String, String> link = new LinkedHashMap<>();
        link.put("rel", rel);
        link.put("href", href);
        return link;
    }
}
