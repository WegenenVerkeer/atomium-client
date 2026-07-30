package be.wegenenverkeer.atomium.client.handler;

/**
 * What a run yielded. Deliberately <em>three</em> counters: on a filtering, deduplicating feed they diverge widely
 * (e.g. 10,000 read → 800 accepted → 120 processed), and exactly that difference is what you want to see. A single
 * number would hide it.
 *
 * @param read      the entries the feed delivered
 * @param accepted of those: what {@link FeedHandler#accepts} found relevant
 * @param processed     of those: what was actually delivered to the handler after dedup <em>and committed</em>
 */
public record FeedRunResult(int read, int accepted, int processed) {
}
