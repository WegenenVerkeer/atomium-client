package be.wegenenverkeer.atomium.client.fetch;

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
 * Reference implementation of {@link FeedPageDecoder} based on Jackson 3, for use in the tests
 * of {@code atomium-client-core}. (The full-fledged production decoder lives in {@code atomium-client-jackson-3};
 * this test copy exists so {@code core} can test itself without a module cycle.)
 */
// deliberately a simplified copy (e.g. without the "empty content" nuance of the production variant); if you
// change the production decoder, check whether this copy needs to be updated to match
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

    private @Nullable Generator generator(@Nullable JsonNode generatorNode) {
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
