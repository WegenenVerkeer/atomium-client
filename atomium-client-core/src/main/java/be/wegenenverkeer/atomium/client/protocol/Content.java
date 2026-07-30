package be.wegenenverkeer.atomium.client.protocol;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The content of an {@link AtomiumEntry}.
 *
 * <p>{@link #value()} is deliberately a <em>raw JSON String</em>: {@code atomium-client-core} does not
 * deserialize the content. The feed consumer deserializes {@code value} into its own domain type and can
 * thus catch a deserialization failure (e.g. an unknown enum value or a type failure) per entry, log it and
 * move on, without blocking the rest of the feed.
 *
 * @param type  the content type; may be {@code null} (many current server implementations leave this
 *              field unset). An empty/blank value is normalized to {@code null}
 * @param value the raw JSON content of the event
 */
public record Content(@Nullable String type, String value) {

    public Content {
        type = Optional.ofNullable(type).map(String::trim).filter(Predicate.not(String::isEmpty)).orElse(null);
    }
}
