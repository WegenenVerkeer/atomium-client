package be.wegenenverkeer.atomium.client.handler;

/**
 * What a run yielded. Deliberately <em>three</em> counters: on a filtering, deduplicating feed they diverge widely
 * (e.g. 10,000 read → 800 accepted → 120 processed), and exactly that difference is what you want to see. A single
 * number would hide it.
 *
 * <p>{@code read} and {@code accepted} count feed entries. {@code processed} is a free measure of the
 * realised, <em>committed</em> work: by default it counts entries too, but a handler may give it its own
 * meaning — say the business entities it upserted, which can be fewer or more than the accepted entries
 * (see {@link ProcessResult}).
 *
 * @param read      the entries the feed delivered
 * @param accepted  of those: what the handler's {@code accepts} found relevant
 * @param processed the realised, committed work (default: entries; the handler chooses the meaning, see
 *                  {@link ProcessResult})
 */
public record FeedRunResult(int read, int accepted, int processed) {
}
