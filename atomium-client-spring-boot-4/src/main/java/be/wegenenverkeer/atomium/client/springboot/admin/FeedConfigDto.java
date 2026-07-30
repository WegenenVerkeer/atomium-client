package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.springboot.AtomiumFeedProperties;

/**
 * The resolved config of one feed.
 *
 * @param feedId the feed
 * @param config the {@link AtomiumFeedProperties} as bound from the app config
 */
public record FeedConfigDto(String feedId, AtomiumFeedProperties config) {
}
