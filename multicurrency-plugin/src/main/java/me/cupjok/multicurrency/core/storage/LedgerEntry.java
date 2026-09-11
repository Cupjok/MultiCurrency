package me.cupjok.multicurrency.core.storage;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.TransactionType;

import java.util.UUID;

/** Raw ledger row. Amounts and balances are minor units of the currency's stored scale. */
public record LedgerEntry(
        long sequence,
        String transactionId,
        String idempotencyKey,
        String currencyId,
        TransactionType type,
        Actor actor,
        UUID account,
        UUID counterparty,
        long amount,
        Long balanceBefore,
        long balanceAfter,
        Long counterpartyBalanceBefore,
        Long counterpartyBalanceAfter,
        String reason,
        String serverId,
        long createdAt) {
}
