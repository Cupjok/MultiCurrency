package me.cupjok.multicurrency.api;

import java.util.Objects;

/**
 * Metadata attached to a mutation.
 *
 * @param actor          who initiated it
 * @param reason         free-text reason stored in the ledger (max 255 chars, longer is cut), may be null
 * @param idempotencyKey optional caller-chosen unique key (max 128 chars). When a transaction with the
 *                       same key has already been committed, the new request is not applied and returns
 *                       {@link FailureReason#DUPLICATE_TRANSACTION}. Namespace it with your plugin name,
 *                       e.g. {@code "dynamicshop3:order:1234"}. May be null.
 */
public record TransactionContext(Actor actor, String reason, String idempotencyKey) {

    public static final int MAX_REASON_LENGTH = 255;
    public static final int MAX_KEY_LENGTH = 128;

    public TransactionContext {
        Objects.requireNonNull(actor, "actor");
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH)) {
            throw new IllegalArgumentException("idempotency key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
    }

    public static TransactionContext of(Actor actor, String reason) {
        return new TransactionContext(actor, reason, null);
    }

    public TransactionContext withIdempotencyKey(String key) {
        return new TransactionContext(actor, reason, key);
    }
}
