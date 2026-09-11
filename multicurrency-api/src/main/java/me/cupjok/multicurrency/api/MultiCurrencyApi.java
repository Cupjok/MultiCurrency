package me.cupjok.multicurrency.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public entry point of the MultiCurrency plugin.
 *
 * <p>Obtain it through Bukkit's {@code ServicesManager} ({@code load(MultiCurrencyApi.class)}) or
 * {@link MultiCurrencyProvider#get()}.
 *
 * <h2>Threading</h2>
 * Every method that touches balances returns a {@link CompletableFuture} that completes on a
 * MultiCurrency database thread, never on the server main thread. Never call {@code join()} or
 * {@code get()} on these futures from the main thread; hop back with the scheduler instead.
 *
 * <h2>Correctness model</h2>
 * <ul>
 *   <li>Every mutation is one atomic database transaction. Balance checks happen inside that
 *       transaction, under row locks, so there is no check-then-act window.</li>
 *   <li>Mutations never complete exceptionally for business failures: inspect
 *       {@link TransactionResult#success()} and {@link TransactionResult#failureReason()}.</li>
 *   <li>Pass an idempotency key in {@link TransactionContext} to make retries safe: a key that was
 *       already committed yields {@link FailureReason#DUPLICATE_TRANSACTION} and is never applied twice.</li>
 *   <li>Currency rules (enabled state, decimal places, balance limit, {@code transfer-enabled}) are
 *       enforced here, in the service layer. No method bypasses them.</li>
 * </ul>
 *
 * <p>Amounts are {@link BigDecimal}. An amount with more decimal places than the currency allows is
 * rejected with {@link FailureReason#INVALID_PRECISION}; it is never rounded or truncated.
 */
public interface MultiCurrencyApi {

    /** API revision. Incremented when methods are added; existing methods keep their contract. */
    int API_VERSION = 1;

    // ------------------------------------------------------------------ currencies

    /** All configured currencies, enabled or not, in configuration order. */
    Collection<Currency> currencies();

    /** Looks a currency up by its stable id (case-insensitive). */
    Optional<Currency> currency(String currencyId);

    /** {@code true} when the currency exists and is enabled. */
    boolean isEnabled(String currencyId);

    // ------------------------------------------------------------------ reads

    /**
     * Current balance. Players without an account yet report the currency's starting balance.
     * Completes exceptionally with {@link CurrencyException} for an unknown currency or storage failure.
     * Disabled currencies can still be read.
     */
    CompletableFuture<BigDecimal> balance(UUID player, String currencyId);

    /**
     * Whether the player's balance is at least {@code amount} at the moment of the read.
     *
     * <p><b>Advisory only.</b> The answer can be stale by the time you act on it. To spend, call
     * {@link #withdraw} directly: it performs the check and the debit atomically and fails with
     * {@link FailureReason#INSUFFICIENT_FUNDS} when the balance is too low.
     */
    CompletableFuture<Boolean> has(UUID player, String currencyId, BigDecimal amount);

    /** Most recent committed transactions touching the player, newest first. {@code currencyId} may be null for all. */
    CompletableFuture<List<TransactionRecord>> history(UUID player, String currencyId, int limit);

    /** Highest balances of a currency, highest first. */
    CompletableFuture<List<BalanceEntry>> top(String currencyId, int limit, int offset);

    // ------------------------------------------------------------------ mutations

    /** Adds {@code amount} (&gt; 0) to the player's balance. System/plugin operation. */
    CompletableFuture<TransactionResult> deposit(UUID player, String currencyId, BigDecimal amount, TransactionContext context);

    /**
     * Atomically removes {@code amount} (&gt; 0) if and only if the balance covers it.
     * This is the correct way to "charge" a player. System/plugin operation.
     */
    CompletableFuture<TransactionResult> withdraw(UUID player, String currencyId, BigDecimal amount, TransactionContext context);

    /** Sets the balance to {@code amount} (&ge; 0). Administrative operation. */
    CompletableFuture<TransactionResult> set(UUID player, String currencyId, BigDecimal amount, TransactionContext context);

    /**
     * Player-to-player transfer: debits {@code from} and credits {@code to} in one atomic transaction.
     *
     * <p>Always subject to the currency's {@code transfer-enabled} setting; there is no override.
     * Fails with {@link FailureReason#TRANSFER_DISABLED}, {@link FailureReason#SELF_TRANSFER},
     * {@link FailureReason#INSUFFICIENT_FUNDS} or {@link FailureReason#BALANCE_LIMIT_EXCEEDED}
     * without changing either balance.
     */
    CompletableFuture<TransactionResult> transfer(UUID from, UUID to, String currencyId, BigDecimal amount, TransactionContext context);
}
