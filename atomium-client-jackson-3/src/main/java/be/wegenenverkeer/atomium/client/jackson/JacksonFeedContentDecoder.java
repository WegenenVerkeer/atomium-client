package be.wegenenverkeer.atomium.client.jackson;

import be.wegenenverkeer.atomium.client.handler.FeedContentDecoder;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

/**
 * Builds a Jackson-based {@link FeedContentDecoder} for a {@link FeedHandler}: the content type {@code C} is
 * derived from the handler's type hierarchy, so the application never has to repeat it, and the raw
 * entry content is deserialized to it with the given {@link JsonMapper}.
 *
 * <p>The derivation happens entirely within Jackson ({@link TypeFactory#findTypeParameters}): it resolves {@code C}
 * even when it runs through an intermediate interface or class ({@code EntryFeedHandler<C> extends FeedHandler<C>})
 * and preserves the type arguments of a generic content type ({@code FeedHandler<List<Foo>>}).
 *
 * <p>A handler that accepts arbitrary content should use {@code JsonNode} as its content type; a type argument
 * {@code Object} cannot be distinguished from a raw type and is therefore rejected.
 */
public final class JacksonFeedContentDecoder {

    private JacksonFeedContentDecoder() {
    }

    /**
     * The {@link FeedContentDecoder} for this handler: deserializes the raw entry content with {@code jsonMapper}
     * to the handler's content type {@code C}.
     *
     * @throws IllegalStateException if the content type cannot be derived from the handler (e.g. a raw
     *                               {@code implements EntryFeedHandler} without a type argument)
     */
    public static <C> FeedContentDecoder<C> of(FeedHandler<C> handler, JsonMapper jsonMapper) {
        JavaType contentType = resolveContentType(handler, jsonMapper);
        return value -> jsonMapper.readValue(value, contentType);
    }

    private static JavaType resolveContentType(FeedHandler<?> handler, JsonMapper jsonMapper) {
        TypeFactory typeFactory = jsonMapper.getTypeFactory();
        JavaType[] typeArguments = typeFactory.findTypeParameters(
                typeFactory.constructType(handler.getClass()), FeedHandler.class);
        // a raw 'implements EntryFeedHandler' (without a type argument) resolves to Object → unusable
        if (typeArguments == null || typeArguments.length == 0 || typeArguments[0].isJavaLangObject()) {
            throw new IllegalStateException(("could not determine the content-type of FeedHandler %s; "
                    + "specify it explicitly (e.g. 'implements EntryFeedHandler<MyEvent>')")
                    .formatted(handler.getClass().getName()));
        }
        return typeArguments[0];
    }
}
