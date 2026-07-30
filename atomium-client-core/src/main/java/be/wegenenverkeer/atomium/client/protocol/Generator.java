package be.wegenenverkeer.atomium.client.protocol;

/**
 * The generator info of a feed (which software produced the feed). Purely informational.
 */
public record Generator(String text, String uri, String version) {
}
