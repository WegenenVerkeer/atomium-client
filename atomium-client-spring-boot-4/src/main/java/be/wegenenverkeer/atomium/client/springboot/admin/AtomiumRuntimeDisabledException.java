package be.wegenenverkeer.atomium.client.springboot.admin;

/**
 * The runtime of this instance is disabled ({@code Feeds.isEnabled()} is {@code false}: this instance does not
 * consume feeds, e.g. a standby in a leader election), so the admin endpoint refuses every operation here — the
 * reads would be misleading, the writes would collide with the instance that does consume — with
 * {@code 503 Service Unavailable} and a {@code Retry-After}: behind a load balancer the retry lands on another
 * instance.
 */
public class AtomiumRuntimeDisabledException extends RuntimeException {

    public AtomiumRuntimeDisabledException() {
        super("the atomium runtime is disabled on this instance (it does not consume feeds); "
                + "retry on the instance that consumes the feeds");
    }
}
