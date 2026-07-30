package be.wegenenverkeer.atomium.client.exception;

/**
 * Thrown when the server returns an unexpected HTTP status (not 200/304).
 */
public class AtomiumHttpException extends AtomiumClientException {

    private final int status;

    public AtomiumHttpException(int status, String relativeLink) {
        super("unexpected HTTP status %d while fetching '%s'".formatted(status, relativeLink));
        this.status = status;
    }

    /**
     * The HTTP status code that was received.
     */
    public int status() {
        return status;
    }
}
