package be.wegenenverkeer.atomium.client.springboot.admin;

import be.wegenenverkeer.atomium.client.handler.FeedHandler;

/**
 * The requested feed does not exist (no registered {@code FeedHandler} with that feedId). The admin endpoint
 * translates this into a {@code 404 Not Found} — an unknown resource, as opposed to an
 * {@link AtomiumAdminValidationException} (an otherwise known feed in a state where the operation is not allowed → 400).
 */
public class AtomiumUnknownFeedException extends RuntimeException {

    public AtomiumUnknownFeedException(String message) {
        super(message);
    }
}
