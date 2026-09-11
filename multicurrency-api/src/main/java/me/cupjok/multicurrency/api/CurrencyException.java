package me.cupjok.multicurrency.api;

/** Exceptional completion of a read operation (mutations report failures via {@link TransactionResult}). */
public class CurrencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;

    public CurrencyException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CurrencyException(FailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
