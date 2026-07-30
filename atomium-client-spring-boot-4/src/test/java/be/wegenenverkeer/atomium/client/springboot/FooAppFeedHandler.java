package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

import org.springframework.stereotype.Component;

/**
 * Handler for the regular test feed ({@code foo-app}). <em>Does</em> support push (overrides
 * {@link FeedHandler#pushEntry}), unlike {@link FooAppEmptyPageFeedHandler}.
 */
@Component
public class FooAppFeedHandler extends RecordingFeedHandler {

    @Override
    public String getFeedId() {
        return "foo-app";
    }

    @Override
    public void pushEntry(FooAppFeedEntry content) {
        recordPush(content);
    }
}
