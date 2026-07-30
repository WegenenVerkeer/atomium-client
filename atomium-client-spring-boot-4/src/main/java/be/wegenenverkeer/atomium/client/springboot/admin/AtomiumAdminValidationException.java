package be.wegenenverkeer.atomium.client.springboot.admin;

/**
 * An admin operation is not allowed in the current state of an (otherwise known) feed: e.g. activating an already
 * active feed, setting the feed pointer while the feed is active, or pushing on a handler that does not support it.
 * The admin endpoint translates this into a {@code 400 Bad Request}. An <em>unknown</em> feed is not a validation
 * error but an {@link AtomiumUnknownFeedException} (→ 404).
 */
public class AtomiumAdminValidationException extends RuntimeException {

    public AtomiumAdminValidationException(String message) {
        super(message);
    }
}
