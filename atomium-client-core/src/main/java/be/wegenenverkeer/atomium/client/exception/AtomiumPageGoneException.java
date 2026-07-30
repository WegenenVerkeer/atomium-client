package be.wegenenverkeer.atomium.client.exception;

/**
 * Thrown on a {@code 410 Gone}: the requested page has been purged and no longer exists.
 *
 * <p>The appropriate reaction is to restart consumption from the oldest page still available.
 * (Servers do not necessarily support purging yet; this exception exists so that consumers can
 * anticipate it today.)
 */
public class AtomiumPageGoneException extends AtomiumClientException {

    public AtomiumPageGoneException(String relativeLink) {
        super("page has been purged (410 Gone): '%s'".formatted(relativeLink));
    }
}
