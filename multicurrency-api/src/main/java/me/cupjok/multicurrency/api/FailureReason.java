package me.cupjok.multicurrency.api;

/** Why a transaction was not applied. New constants may be added in later API versions. */
public enum FailureReason {
    /** No currency with that id is configured. */
    UNKNOWN_CURRENCY,
    /** The currency exists but is disabled (or disabled because its stored configuration conflicts). */
    CURRENCY_DISABLED,
    /** Amount is null, zero/negative where a positive amount is required, or otherwise invalid. */
    INVALID_AMOUNT,
    /** Amount has more decimal places than the currency's scale. Never rounded. */
    INVALID_PRECISION,
    /** Amount is larger than the currency can represent or than its maximum balance. */
    AMOUNT_TOO_LARGE,
    /** Balance does not cover the debit. */
    INSUFFICIENT_FUNDS,
    /** The credit would push the balance above the currency's maximum balance. */
    BALANCE_LIMIT_EXCEEDED,
    /** The currency has {@code transfer-enabled: false}. */
    TRANSFER_DISABLED,
    /** Sender and recipient are the same player. */
    SELF_TRANSFER,
    /** A transaction with the same idempotency key was already committed; nothing was applied now. */
    DUPLICATE_TRANSACTION,
    /** A required argument (player, context) was missing or malformed. */
    INVALID_ARGUMENT,
    /** The plugin is starting, stopping or failed to initialise its storage. Nothing was applied. */
    SERVICE_UNAVAILABLE,
    /** The database failed and the transaction was rolled back. Nothing was applied. */
    STORAGE_ERROR,
    /**
     * The database failed while committing, so it is unknown whether the transaction was applied.
     * Retry with the same idempotency key to find out safely.
     */
    OUTCOME_UNKNOWN
}
