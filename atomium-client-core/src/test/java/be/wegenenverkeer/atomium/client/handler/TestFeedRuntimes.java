package be.wegenenverkeer.atomium.client.handler;

/**
 * Test support: builds {@link FeedRuntime}s without the full {@link Feed} assembly, for tests that only
 * need the {@link FeedRunner} side (scheduler and registry tests).
 */
public final class TestFeedRuntimes {

    private TestFeedRuntimes() {
    }

    /** A runtime around just a runner; the feed definition and the pusher are left unset. */
    public static FeedRuntime withRunnerOnly(FeedRunner runner) {
        return new FeedRuntime(null, runner, null, new FeedRuntime.Progress(java.time.Clock.systemUTC()));
    }
}
