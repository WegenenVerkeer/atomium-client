package be.wegenenverkeer.atomium.client.springboot.admin;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A raw fetched feed page (diagnostics): the HTTP status, headers and body. The {@code body} is an
 * {@code Object} so that a JSON body is serialized as <em>nested JSON</em> (instead of as an escaped string);
 * if the body is not valid JSON, it stays a string.
 */
public record RawPageDto(int status, Map<String, String> headers, @Nullable Object body) {
}
