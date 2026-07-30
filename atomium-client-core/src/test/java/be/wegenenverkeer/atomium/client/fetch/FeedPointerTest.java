package be.wegenenverkeer.atomium.client.fetch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit test on constructing a {@link FeedPointer} from the persisted fields.
 */
class FeedPointerTest {

    /**
     * The 4-arg constructor rebuilds a pointer from the four columns ({@code last_event_page_link},
     * {@code last_event_id}, {@code next_fetch_page_link}, {@code next_fetch_page_etag}) and derives the
     * fetch {@code filterEventId}: equal to the {@code lastEventId} as long as the next fetch happens on the
     * same page as the last processed event, otherwise empty.
     */
    @Nested
    class FromPersistedFields {

        @Test
        void whenFetchIsOnTheSamePageAsTheLastEventFilterEqualsLastEventId() {
            // we keep polling the same (incomplete) page → dedup from the last processed event
            FeedPointer pointer = new FeedPointer("/2", "id-008", "/2", "etag-v1");

            assertThat(pointer.lastEvent()).isEqualTo(new EventCoordinate("/2", "id-008"));
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/2", "id-008", "etag-v1"));
        }

        @Test
        void whenFetchHasJumpedToAYoungerPageThereIsNoFilter() {
            // the previous page filled up → we read the younger page from the start (no filter, no etag)
            FeedPointer pointer = new FeedPointer("/1", "id-006", "/2", null);

            assertThat(pointer.lastEvent()).isEqualTo(new EventCoordinate("/1", "id-006"));
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/2", null, null));
        }

        @Test
        void whenThereIsNoLastEventYetTheCoordinateAndTheFilterStayEmpty() {
            // genesis-like: nothing processed yet (both last_event columns empty)
            FeedPointer pointer = new FeedPointer(null, null, "/0", null);

            assertThat(pointer.lastEvent()).isNull();
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/0", null, null));
        }

        @Test
        void whenOnlyOneOfTheLastEventFieldsIsSetItDoesNotCountAsLastEvent() {
            // half-populated persistence (only the page, no id) → no lastEvent, no filter
            FeedPointer pointer = new FeedPointer("/2", null, "/2", "etag");

            assertThat(pointer.lastEvent()).isNull();
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/2", null, "etag"));
        }
    }

    @Nested
    class Factories {

        @Test
        void genesisConstructorReadsTheWholePageWithoutFilterOrEtag() {
            FeedPointer pointer = new FeedPointer("/0");

            assertThat(pointer.lastEvent()).isNull();
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/0", null, null));
        }

        @Test
        void resumeAfterFiltersUpToAndIncludingTheProcessedEventOnTheSamePage() {
            FeedPointer pointer = FeedPointer.resumeAfter(new EventCoordinate("/5", "id-042"));

            assertThat(pointer.lastEvent()).isEqualTo(new EventCoordinate("/5", "id-042"));
            assertThat(pointer.nextFetch()).isEqualTo(new FetchCoordinate("/5", "id-042", null));
        }
    }
}
