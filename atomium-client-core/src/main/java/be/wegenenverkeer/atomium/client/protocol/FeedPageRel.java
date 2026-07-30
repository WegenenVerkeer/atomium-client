package be.wegenenverkeer.atomium.client.protocol;

/**
 * The {@code rel} names as they appear in the Atomium protocol are historical and can no longer be changed, even though they are confusing:
 * the <em>older</em> page carries {@code next}
 * and the <em>younger</em> page {@code previous}.
 *
 * <p>To avoid confusion, we deliberately use different, age-based terminology: "oldest/youngest/older/younger".
 */
public enum FeedPageRel {

    /**
     * This page itself.
     */
    SELF("self"),

    /**
     * The oldest page.
     */
    OLDEST("last"),

    /**
     * The <em>older</em> page (towards the beginning of the feed).
     */
    OLDER("next"),

    /**
     * The <em>younger</em> page (towards the head of the feed).
     */
    YOUNGER("previous");

    private final String code;

    FeedPageRel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
