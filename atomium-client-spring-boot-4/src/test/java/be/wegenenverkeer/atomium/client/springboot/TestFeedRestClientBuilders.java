package be.wegenenverkeer.atomium.client.springboot;

import org.springframework.web.client.RestClient;

/**
 * A minimal {@link FeedRestClientBuilders} for the tests: a simple RestClient to the (WireMock) feed based
 * on the generic feed {@code url}. Proves that the generic machinery works with just this one bean — content mapper,
 * executor and backoff come as framework defaults from the module itself.
 */
class TestFeedRestClientBuilders implements FeedRestClientBuilders {

    @Override
    public RestClient.Builder restClientBuilderFor(String feedId, AtomiumFeedProperties properties) {
        // the base url comes from the generic feed url (no security needed in the tests)
        return RestClient.builder().baseUrl(properties.url());
    }
}
