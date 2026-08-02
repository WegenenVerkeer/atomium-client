package be.wegenenverkeer.atomium.client.core.demo.fullmonty;

/**
 * The deserialized domain type of a {@code full-monty} feed entry. Where {@code SimpleDemoFeedHandler} works with a raw
 * {@code JsonNode}, this demo shows that a handler may just as well pick a <em>record of its own</em> as content type: the
 * framework deserializes the raw JSON ({@code {"aField": "fieldValue-7"}}) straight into it with the content mapper,
 * and the handler gets typed fields ({@code content.aField()}) instead of {@code node.get("aField")}.
 *
 * @param aField the only field the demo feed ({@code DemoFeedEndpoint}) supplies per entry
 */
public record MontyContent(String aField) {
}
