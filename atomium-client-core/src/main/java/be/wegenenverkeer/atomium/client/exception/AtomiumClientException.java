package be.wegenenverkeer.atomium.client.exception;

/**
 * Base of all exceptions thrown by {@code atomium-client-core}.
 */
public class AtomiumClientException extends RuntimeException {

    public AtomiumClientException(String message) {
        super(message);
    }

    public AtomiumClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
