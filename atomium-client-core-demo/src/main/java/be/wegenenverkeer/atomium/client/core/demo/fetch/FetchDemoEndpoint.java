package be.wegenenverkeer.atomium.client.core.demo.fetch;

import be.wegenenverkeer.atomium.client.core.demo.DemoAtomiumClients;
import be.wegenenverkeer.atomium.client.core.demo.DemoProperties;
import be.wegenenverkeer.atomium.client.fetch.AtomiumClient;
import be.wegenenverkeer.atomium.client.fetch.FeedPointer;
import be.wegenenverkeer.atomium.client.fetch.FetchEntry;
import be.wegenenverkeer.atomium.client.fetch.FetchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>fetch</b> demo: the low-level fetch API used directly, without the handler API. A single GET reads the
 * complete feed from the oldest page up to the head — the classic read loop: {@code fetch(pointer)} until the
 * feed has no further pages, reading off the entries per page and advancing the pointer. The position only
 * lives in this request (no persistence): every call reads everything again.
 */
@RestController
@RequestMapping("/rest/demo/fetch")
class FetchDemoEndpoint {

    private final AtomiumClient atomiumClient;

    FetchDemoEndpoint(DemoProperties properties) {
        this.atomiumClient = DemoAtomiumClients.atomiumClient("fetch", properties.feedUrl());
    }

    /** Reads the complete feed from start (oldest page) to end (head) and returns the entries. */
    @GetMapping
    public Map<String, Object> readCompleteFeed() {
        List<Map<String, String>> entries = new ArrayList<>();
        FeedPointer pointer = atomiumClient.pointerToOldest();
        while (true) {
            FetchResult result = atomiumClient.fetch(pointer).orElse(null);
            if (result == null) {
                break;   // 304 Not Modified: nothing new since the given pointer
            }
            for (FetchEntry fetchEntry : result.fetchEntries()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", fetchEntry.entry().id());
                entry.put("updated", fetchEntry.entry().updated().toString());
                entry.put("content", fetchEntry.entry().content().value());
                entries.add(entry);
            }
            pointer = result.nextFeedPointer();
            if (!result.feedHasMorePages()) {
                break;   // the head has been processed
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", entries.size());
        response.put("entries", entries);
        return response;
    }
}
