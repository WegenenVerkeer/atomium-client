package be.wegenenverkeer.atomium.client.springboot;


import org.springframework.stereotype.Component;

/**
 * Handler for the test feed with an empty, complete middle page ({@code foo-app-empty-page}). Deliberately does
 * <em>not</em> support push (does not implement {@link be.wegenenverkeer.atomium.client.handler.FeedPusher}), so the admin test can validate
 * that a push then yields a 400.
 */
@Component
class FooAppEmptyPageFeedHandler extends RecordingFeedHandler {

    @Override
    public String getFeedId() {
        return "foo-app-empty-page";
    }
}
