package me.cupjok.multicurrency.core.cache;

import java.util.UUID;

/** Cache key of one player's balance in one currency. */
public record BalanceKey(UUID player, String currency) {
}
