package be.wegenenverkeer.atomium.client.core.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The demo config ({@code demo.*}). The handler API itself has no properties mechanism — the assembly code
 * decides where the values come from. This demo binds them via Spring Boot and passes them on to the
 * {@code Feed} builders; another stack reads them from its own config.
 *
 * @param feedUrl       the base url of the source feed (here: the app's own in-memory {@link DemoFeedEndpoint})
 * @param queryInterval the poll frequency of the feeds (short, so the demo stays lively)
 * @param simple        the config of the {@code simple} feed
 * @param fullMonty     the config of the {@code full-monty} feed
 * @param simpleProcessing the config of the {@code simple-processing} feed
 */
@ConfigurationProperties("demo")
public record DemoProperties(
        String feedUrl,
        @DefaultValue("5s") Duration queryInterval,
        @DefaultValue Simple simple,
        @DefaultValue FullMonty fullMonty,
        @DefaultValue SimpleProcessing simpleProcessing
) {

    public record Simple(@DefaultValue("true") boolean activeOnStartup) {
    }

    public record FullMonty(@DefaultValue("false") boolean activeOnStartup) {
    }

    public record SimpleProcessing(
            @DefaultValue("false") boolean activeOnStartup,
            @DefaultValue("5") int maxProcessingSize,
            @DefaultValue("10") int maxUncommittedPages
    ) {
    }
}
