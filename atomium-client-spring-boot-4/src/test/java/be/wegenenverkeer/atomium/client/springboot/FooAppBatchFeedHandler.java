package be.wegenenverkeer.atomium.client.springboot;

import org.springframework.stereotype.Component;

/**
 * Handler for the batch test feed ({@code foo-app-batch}): threshold 3 via {@code processing.max-size}, so the
 * dedup and threshold scenarios stay short.
 */
@Component
public class FooAppBatchFeedHandler extends RecordingBatchedFeedHandler {

    @Override
    public String getFeedId() {
        return "foo-app-batch";
    }
}
