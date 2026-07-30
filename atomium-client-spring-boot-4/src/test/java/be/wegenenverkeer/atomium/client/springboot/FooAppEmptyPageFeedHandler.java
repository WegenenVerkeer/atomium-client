package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import org.springframework.stereotype.Component;

/**
 * Handler for the test feed with an empty, complete middle page ({@code foo-app-empty-page}). Deliberately does
 * <em>not</em> support push (no override of {@link FeedHandler#pushEntry}), so the admin test can validate
 * that a push then yields a 400.
 */
@Component
class FooAppEmptyPageFeedHandler extends RecordingFeedHandler {

    @Override
    public String getFeedId() {
        return "foo-app-empty-page";
    }
}
