package me.cupjok.multicurrency.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One committed ledger entry.
 *
 * @param account                  account the operation applies to (transfer: the sender)
 * @param counterparty             transfer recipient, otherwise null
 * @param balanceBefore            account balance before, null for {@link TransactionType#INITIAL}
 * @param counterpartyBalanceBefore transfer only
 */
public record TransactionRecord(
        long sequence,
        String transactionId,
        String idempotencyKey,
        String currencyId,
        TransactionType type,
        Actor actor,
        UUID account,
        UUID counterparty,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        BigDecimal counterpartyBalanceBefore,
        BigDecimal counterpartyBalanceAfter,
        String reason,
        String serverId,
        Instant timestamp) {
}
