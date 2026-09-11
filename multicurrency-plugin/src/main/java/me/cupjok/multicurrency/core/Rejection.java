package me.cupjok.multicurrency.core;

import me.cupjok.multicurrency.api.FailureReason;

/**
 * A business-rule refusal raised inside the service or a database transaction. Thrown out of a
 * transaction body it rolls the transaction back; nothing is applied.
 */
public final class Rejection extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;
    private final String transactionId;

    public Rejection(FailureReason reason) {
        this(reason, null);
    }

    public Rejection(FailureReason reason, String transactionId) {
        super(reason.name(), null, false, false);
        this.reason = reason;
        this.transactionId = transactionId;
    }

    public FailureReason reason() {
        return reason;
    }

    /** For {@link FailureReason#DUPLICATE_TRANSACTION}: the original transaction id. */
    public String transactionId() {
        return transactionId;
    }
}
