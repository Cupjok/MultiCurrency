package me.cupjok.multicurrency.api;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Outcome of a mutation.
 *
 * @param success                  {@code true} when the transaction was committed
 * @param failureReason            why it was not applied; null on success
 * @param transactionId            ledger id of the committed transaction; on
 *                                 {@link FailureReason#DUPLICATE_TRANSACTION} the id of the original; otherwise null
 * @param type                     operation type
 * @param currencyId               currency id as requested (normalised to lower case)
 * @param amount                   requested amount
 * @param balanceAfter             balance of the account (transfer: the sender) after commit; null unless success
 * @param counterpartyBalanceAfter transfer only: recipient's balance after commit; otherwise null
 */
public record TransactionResult(
        boolean success,
        FailureReason failureReason,
        String transactionId,
        TransactionType type,
        String currencyId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        BigDecimal counterpartyBalanceAfter) {

    public static TransactionResult ok(String transactionId, TransactionType type, String currencyId, BigDecimal amount,
                                       BigDecimal balanceAfter, BigDecimal counterpartyBalanceAfter) {
        return new TransactionResult(true, null, transactionId, type, currencyId, amount, balanceAfter, counterpartyBalanceAfter);
    }

    public static TransactionResult failed(FailureReason reason, TransactionType type, String currencyId, BigDecimal amount) {
        return new TransactionResult(false, reason, null, type, currencyId, amount, null, null);
    }

    public static TransactionResult duplicate(String originalTransactionId, TransactionType type, String currencyId, BigDecimal amount) {
        return new TransactionResult(false, FailureReason.DUPLICATE_TRANSACTION, originalTransactionId, type, currencyId, amount, null, null);
    }

    public Optional<FailureReason> failure() {
        return Optional.ofNullable(failureReason);
    }
}
