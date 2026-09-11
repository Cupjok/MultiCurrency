package me.cupjok.multicurrency.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of a balance leaderboard.
 *
 * @param lastKnownName last name MultiCurrency saw for the player, may be null
 */
public record BalanceEntry(UUID player, String lastKnownName, BigDecimal balance) {
}
