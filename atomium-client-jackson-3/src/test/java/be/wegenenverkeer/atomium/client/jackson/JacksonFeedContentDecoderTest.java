package be.wegenenverkeer.atomium.client.jackson;

import be.wegenenverkeer.atomium.client.handler.EntryFeedHandler;
import be.wegenenverkeer.atomium.client.handler.FeedContentDecoder;
import be.wegenenverkeer.atomium.client.handler.FeedHandler;
import be.wegenenverkeer.atomium.client.protocol.AtomiumEntry;
import be.wegenenverkeer.atomium.client.protocol.FeedPageMetadata;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JacksonFeedContentDecoderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record MyEvent(String aField) {
    }

    /**
     * The content type runs through an intermediate interface ({@code EntryFeedHandler<C> extends FeedHandler<C>})
     * and is a type <em>variable</em> there. The resolution has to resolve it to the actual type — otherwise
     * Jackson would decode to a {@code LinkedHashMap} and the handler would hit a {@code ClassCastException}.
     */
    @Test
    void derivesTheContentTypeThroughTheIntermediateInterface() {
        FeedContentDecoder<MyEvent> decoder = JacksonFeedContentDecoder.of(new MyEventHandler(), MAPPER);

        MyEvent event = decoder.readFeedContent("{\"aField\": \"value-1\"}");

        assertThat(event).isEqualTo(new MyEvent("value-1"));
    }

    /** A generic content type ({@code EntryFeedHandler<List<MyEvent>>}) keeps its type arguments. */
    @Test
    void keepsTheTypeArgumentsOfAGenericContentType() {
        FeedContentDecoder<List<MyEvent>> decoder =
                JacksonFeedContentDecoder.of(new MyEventListHandler(), MAPPER);

        List<MyEvent> events = decoder.readFeedContent("[{\"aField\": \"value-1\"}, {\"aField\": \"value-2\"}]");

        assertThat(events).containsExactly(new MyEvent("value-1"), new MyEvent("value-2"));
    }

    /** The content type is also resolved through an intermediate <em>class</em> hierarchy. */
    @Test
    void derivesTheContentTypeThroughAnIntermediateClass() {
        FeedContentDecoder<MyEvent> decoder = JacksonFeedContentDecoder.of(new ConcreteHandler(), MAPPER);

        assertThat(decoder.readFeedContent("{\"aField\": \"value-1\"}")).isEqualTo(new MyEvent("value-1"));
    }

    /** The content type is also resolved for an anonymous class. */
    @Test
    void derivesTheContentTypeForAnAnonymousClass() {
        EntryFeedHandler<MyEvent> anonymous = new EntryFeedHandler<>() {
            @Override
            public String getFeedId() {
                return "feed";
            }

            @Override
            public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, MyEvent content) {
            }
        };

        FeedContentDecoder<MyEvent> decoder = JacksonFeedContentDecoder.of(anonymous, MAPPER);

        assertThat(decoder.readFeedContent("{\"aField\": \"value-1\"}")).isEqualTo(new MyEvent("value-1"));
    }

    /** A raw {@code implements EntryFeedHandler} (without a type argument) → fail-fast with a clear message. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aHandlerWithoutTypeArgumentFailsWithAClearMessage() {
        FeedHandler rawHandler = new RawHandler();

        assertThatIllegalStateException()
                .isThrownBy(() -> JacksonFeedContentDecoder.of(rawHandler, MAPPER))
                .withMessageContaining("content-type")
                .withMessageContaining("RawHandler");
    }

    /** Intermediate generic <em>class</em> in the hierarchy. */
    private abstract static class BaseHandler<C> implements EntryFeedHandler<C> {
        @Override
        public String getFeedId() {
            return "feed";
        }
    }

    private static class ConcreteHandler extends BaseHandler<MyEvent> {
        @Override
        public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, MyEvent content) {
        }
    }

    private static class MyEventHandler implements EntryFeedHandler<MyEvent> {
        @Override
        public String getFeedId() {
            return "feed";
        }

        @Override
        public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, MyEvent content) {
        }
    }

    private static class MyEventListHandler implements EntryFeedHandler<List<MyEvent>> {
        @Override
        public String getFeedId() {
            return "feed";
        }

        @Override
        public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, List<MyEvent> content) {
        }
    }

    @SuppressWarnings("rawtypes")
    private static class RawHandler implements EntryFeedHandler {
        @Override
        public String getFeedId() {
            return "feed";
        }

        @Override
        public void onEntry(FeedPageMetadata pageMetadata, AtomiumEntry entry, Object content) {
        }
    }
}
