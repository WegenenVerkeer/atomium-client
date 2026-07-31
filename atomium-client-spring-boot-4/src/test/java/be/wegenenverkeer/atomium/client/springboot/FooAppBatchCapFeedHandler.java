package be.wegenenverkeer.atomium.client.springboot;

import org.springframework.stereotype.Component;

/**
 * Handler for the safety-net test feed ({@code foo-app-batch-cap}): a high threshold (100) that is never reached,
 * combined with {@code processing.max-uncommitted-pages: 2}. That leaves the page safety net as the only thing that can
 * trigger a flush — exactly what the test wants to isolate.
 */
@Component
public class FooAppBatchCapFeedHandler extends RecordingBatchedFeedHandler {

    @Override
    public String getFeedId() {
        return "foo-app-batch-cap";
    }
}
