package be.wegenenverkeer.atomium.client.springboot;

import be.wegenenverkeer.atomium.client.handler.FeedPusher;

import org.springframework.stereotype.Component;

/**
 * Handler for the regular test feed ({@code foo-app}). <em>Does</em> support push (implements
 * {@link FeedPusher}), unlike {@link FooAppEmptyPageFeedHandler}.
 */
@Component
public class FooAppFeedHandler extends RecordingFeedHandler implements FeedPusher<FooAppFeedEntry> {

    @Override
    public String getFeedId() {
        return "foo-app";
    }

    @Override
    public void pushEntry(FooAppFeedEntry content) {
        recordPush(content);
    }
}
