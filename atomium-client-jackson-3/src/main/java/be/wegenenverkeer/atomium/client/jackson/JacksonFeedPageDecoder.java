package be.wegenenverkeer.atomium.client.jackson;

import be.wegenenverkeer.atomium.client.exception.AtomiumInvalidPageException;
import be.wegenenverkeer.atomium.client.port.FeedPageDecoder;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.Content;
import be.wegenenverkeer.atomium.client.protocol.FeedPage;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import be.wegenenverkeer.atomium.client.protocol.Generator;
import be.wegenenverkeer.atomium.client.protocol.Link;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FeedPageDecoder} based on Jackson 3 ({@code tools.jackson}).
 *
 * <p>The decoder uses its <em>own</em>, internally created {@link JsonMapper}: it decodes only the
 * Atomium envelope (id, links, and per entry id/updated/links/content) and keeps {@code content.value} as a
 * <strong>raw JSON String</strong>. That makes it independent of the Jackson config the
 * application uses for its domain content. The feed consumer decodes {@code content.value} itself.
 */
public final class JacksonFeedPageDecoder implements FeedPageDecoder {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Override
    public FeedPage readFeedPage(String json) {
        JsonNode root = read(json);
        FeedPageMetadata metadata = new FeedPageMetadata(
                requiredText(root, "id"),
                requiredText(root, "base"),
                requiredText(root, "title"),
                generator(root.get("generator")),
                time(root, "updated"),
                links(root.get("links"))
        );
        return new FeedPage(metadata, entries(root.get("entries")));
    }

    private JsonNode read(String json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw new AtomiumInvalidPageException("invalid JSON for the feed page", e);
        }
        if (root == null || !root.isObject()) {
            throw new AtomiumInvalidPageException("expected a JSON object for the feed page");
        }
        return root;
    }

    private List<AtomiumEntry> entries(@Nullable JsonNode entriesNode) {
        List<AtomiumEntry> entries = new ArrayList<>();
        if (entriesNode == null) {
            return entries;
        }
        for (JsonNode entryNode : entriesNode) {
            verifyType(entryNode.get("_type"));
            entries.add(new AtomiumEntry(
                    requiredText(entryNode, "id"),
                    time(entryNode, "updated"),
                    content(entryNode.get("content")),
                    links(entryNode.get("links"))
            ));
        }
        return entries;
    }

    private void verifyType(@Nullable JsonNode typeNode) {
        if (typeNode == null || typeNode.isNull()) {
            return;
        }
        String type = typeNode.asString("");
        if (!type.isEmpty() && !type.equals("atom")) {
            throw new AtomiumInvalidPageException("unsupported entry _type '%s' (only 'atom' is supported)".formatted(type));
        }
    }

    private Content content(@Nullable JsonNode contentNode) {
        // Missing content or value → an empty value. Deliberately not an envelope failure: the envelope is valid,
        // only the payload is empty. An empty value then fails (like any invalid payload) only when decoding
        // to the domain type in the feed consumer, with entry context (FeedEntryPhase.DECODE).
        if (contentNode == null || contentNode.isNull()) {
            return new Content("", "");
        }
        JsonNode valueNode = contentNode.get("value");
        // value stays raw JSON: the tree structure is written back to a compact JSON String
        String rawValue = valueNode == null ? "" : valueNode.toString();
        return new Content(textOf(contentNode.get("type"), ""), rawValue);
    }

    private List<Link> links(@Nullable JsonNode linksNode) {
        List<Link> links = new ArrayList<>();
        if (linksNode == null) {
            return links;
        }
        for (JsonNode linkNode : linksNode) {
            links.add(new Link(requiredText(linkNode, "rel"), requiredText(linkNode, "href")));
        }
        return links;
    }

    private Generator generator(@Nullable JsonNode generatorNode) {
        if (generatorNode == null || generatorNode.isNull()) {
            throw new AtomiumInvalidPageException("generator is missing");
        }
        return new Generator(
                requiredText(generatorNode, "text"),
                requiredText(generatorNode, "uri"),
                requiredText(generatorNode, "version")
        );
    }

    private OffsetDateTime time(JsonNode node, String field) {
        String text = requiredText(node, field);
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomiumInvalidPageException("invalid time in field '%s': %s".formatted(field, text), e);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || fieldNode.isNull()) {
            throw new AtomiumInvalidPageException("required field '%s' is missing".formatted(field));
        }
        return fieldNode.asString();
    }

    private String textOf(@Nullable JsonNode node, String defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asString(defaultValue);
    }
}
