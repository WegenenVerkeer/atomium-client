package be.wegenenverkeer.atomium.client.exception;

/**
 * Thrown when a feed page envelope cannot be parsed (invalid JSON) or is invalid (invalid type, missing
 * required field, …).
 *
 * <p>Note: this only concerns the <em>envelope</em>. Invalid <em>content</em> of an individual
 * entry never leads to this exception — content remains a raw String and is deserialized by the
 * feed consumer.
 */
public class AtomiumInvalidPageException extends AtomiumClientException {

    public AtomiumInvalidPageException(String message) {
        super(message);
    }

    public AtomiumInvalidPageException(String message, Throwable cause) {
        super(message, cause);
    }
}
