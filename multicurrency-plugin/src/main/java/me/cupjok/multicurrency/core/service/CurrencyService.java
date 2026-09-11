package me.cupjok.multicurrency.core.service;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.BalanceEntry;
import me.cupjok.multicurrency.api.Currency;
import me.cupjok.multicurrency.api.CurrencyException;
import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.api.MultiCurrencyApi;
import me.cupjok.multicurrency.api.TransactionContext;
import me.cupjok.multicurrency.api.TransactionRecord;
import me.cupjok.multicurrency.api.TransactionResult;
import me.cupjok.multicurrency.api.TransactionType;
import me.cupjok.multicurrency.core.Rejection;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import me.cupjok.multicurrency.core.storage.CurrencyRepository;
import me.cupjok.multicurrency.core.storage.Database;
import me.cupjok.multicurrency.core.storage.LedgerEntry;
import me.cupjok.multicurrency.core.storage.SchemaMigrator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The authoritative business layer. Commands and the public API both end here; neither implements
 * balance logic of its own.
 *
 * <p>Rules enforced for every call, regardless of caller:
 * <ul>
 *   <li>the currency must exist and (for mutations) be enabled;</li>
 *   <li>amounts must be positive (non-negative for {@code set}), within the currency's scale and limit;</li>
 *   <li>transfers require {@code transfer-enabled: true} and distinct players;</li>
 *   <li>balances never go below zero or above the maximum.</li>
 * </ul>
 *
 * <p>Every check that depends on a balance runs inside the same database transaction as the write,
 * after the row has been locked, and the write itself is a compare-and-set. This removes the
 * check-then-act window that lets concurrent requests spend the same money twice.
 */
public final class CurrencyService implements MultiCurrencyApi, AutoCloseable {

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int MAX_QUERY_LIMIT = 100;
    private static final int KNOWN_ACCOUNT_CACHE_LIMIT = 200_000;
    private static final Actor STARTING_BALANCE_ACTOR = Actor.system("starting-balance");

    private final Database db;
    private final CurrencyRepository repo;
    private final String serverId;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    /** Hint only: accounts known to exist. A stale entry is detected and repaired, never trusted blindly. */
    private final Set<String> knownAccounts = ConcurrentHashMap.newKeySet();

    private volatile CurrencyRegistry registry = CurrencyRegistry.empty();
    private volatile Map<String, Integer> storedScales = Map.of();
    private volatile Set<String> conflicted = Set.of();
    private volatile boolean closed;
    private volatile BiConsumer<UUID, String> balanceListener;
    private final Object registryLock = new Object();
    private final AtomicLong reloadSequence = new AtomicLong();
    private long appliedSequence;

    public CurrencyService(Database db, String serverId, int threads, Logger logger) {
        this.db = Objects.requireNonNull(db, "db");
        this.repo = new CurrencyRepository(db);
        this.serverId = serverId == null || serverId.isBlank() ? "default" : serverId.substring(0, Math.min(64, serverId.length()));
        this.logger = logger == null ? Logger.getLogger("MultiCurrency") : logger;
        AtomicInteger n = new AtomicInteger();
        int size = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
            Thread t = new Thread(r, "MultiCurrency-DB-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    // ================================================================== lifecycle

    /** Migrates the schema and loads currencies asynchronously. Operations submitted earlier wait for it. */
    public CompletableFuture<Void> start(CurrencyRegistry configured) {
        try {
            executor.execute(() -> {
                try {
                    new SchemaMigrator(db, logger).migrate();
                    applyRegistry(configured, reloadSequence.incrementAndGet());
                    ready.complete(null);
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "MultiCurrency storage failed to initialise; all operations are refused", t);
                    logConnectionHint(t);
                    ready.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            ready.completeExceptionally(e);
        }
        return ready;
    }

    /** Applies a new currency configuration (e.g. {@code /currency reload}). Storage settings are not reloaded. */
    public CompletableFuture<CurrencyRegistry> reload(CurrencyRegistry configured) {
        // Taken at call time: when reloads overlap, the configuration submitted last wins.
        long sequence = reloadSequence.incrementAndGet();
        CompletableFuture<CurrencyRegistry> out = new CompletableFuture<>();
        ready.whenComplete((v, err) -> {
            if (err != null || closed) {
                out.completeExceptionally(new CurrencyException(FailureReason.SERVICE_UNAVAILABLE, "service unavailable"));
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        out.complete(applyRegistry(configured, sequence));
                    } catch (Throwable t) {
                        logger.log(Level.SEVERE, "Currency reload failed; the previous configuration stays active", t);
                        out.completeExceptionally(t);
                    }
                });
            } catch (RejectedExecutionException e) {
                out.completeExceptionally(new CurrencyException(FailureReason.SERVICE_UNAVAILABLE, "service unavailable"));
            }
        });
        return out;
    }

    /**
     * Registers new currencies in the database and fails closed on scale conflicts: a currency whose
     * configured scale differs from the scale its balances were stored with is disabled, because
     * reinterpreting stored minor units would silently multiply or divide every balance.
     *
     * <p>A currency is registered (its scale locked) the first time it is loaded <em>enabled</em>. A
     * disabled currency cannot hold balances (every mutation is refused), so until it is enabled for the
     * first time its decimal places can still be corrected safely.
     *
     * <p>Serialised, and an older configuration never replaces a newer one that was already applied.
     */
    private CurrencyRegistry applyRegistry(CurrencyRegistry configured, long sequence) throws Exception {
        synchronized (registryLock) {
            if (sequence < appliedSequence) {
                return registry;
            }
            CurrencyRegistry result = registerAndCheck(configured);
            appliedSequence = sequence;
            return result;
        }
    }

    private CurrencyRegistry registerAndCheck(CurrencyRegistry configured) throws Exception {
        Map<String, Integer> stored = new HashMap<>(db.transaction(repo::loadScales));
        for (CurrencyDefinition c : configured.all()) {
            if (stored.containsKey(c.id()) || !c.enabled()) {
                continue;
            }
            try {
                db.transaction(conn -> {
                    repo.insertCurrency(conn, c.id(), c.scale(), System.currentTimeMillis());
                    return null;
                });
            } catch (SQLException e) {
                if (!db.isUniqueViolation(e)) {
                    throw e;
                }
                // Another server registered it concurrently; re-read below.
            }
        }
        stored = new HashMap<>(db.transaction(repo::loadScales));
        List<CurrencyDefinition> effective = new ArrayList<>();
        Set<String> conflicts = new HashSet<>();
        for (CurrencyDefinition c : configured.all()) {
            Integer scale = stored.get(c.id());
            if (scale != null && scale != c.scale()) {
                logger.severe("Currency '" + c.id() + "' is configured with scale " + c.scale() + " but its balances are stored with scale "
                        + scale + ". It is DISABLED until the configured scale is changed back to " + scale + ".");
                effective.add(c.withEnabled(false));
                conflicts.add(c.id());
            } else {
                effective.add(c);
            }
        }
        CurrencyRegistry result = new CurrencyRegistry(effective);
        this.storedScales = Map.copyOf(stored);
        this.conflicted = Set.copyOf(conflicts);
        this.registry = result;
        return result;
    }

    /** Explains the MySQL 8 authentication failure that the stack trace alone does not. */
    private void logConnectionHint(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String message = String.valueOf(c.getMessage());
            if (message.contains("RSA public key is not available")) {
                logger.severe("MySQL 8 uses caching_sha2_password. Connect over TLS (storage.mariadb.properties: "
                        + "sslMode: verify-full, or trust for a self-signed certificate) or, on a trusted private network only, "
                        + "set allowPublicKeyRetrieval: true.");
                return;
            }
        }
    }

    /** Stops accepting work, waits for in-flight transactions, then closes the pool. */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Database work still pending after 30s at shutdown; interrupting. "
                        + "Uncommitted transactions are rolled back by the database.");
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    public boolean isReady() {
        return ready.isDone() && !ready.isCompletedExceptionally() && !closed;
    }

    public CurrencyRegistry registry() {
        return registry;
    }

    /** Currencies disabled because their configured scale conflicts with stored data. */
    public Set<String> conflictedCurrencies() {
        return conflicted;
    }

    // ================================================================== currencies

    @Override
    public Collection<Currency> currencies() {
        return List.copyOf(registry.all());
    }

    @Override
    public Optional<Currency> currency(String currencyId) {
        return registry.find(currencyId).map(c -> c);
    }

    @Override
    public boolean isEnabled(String currencyId) {
        return registry.find(currencyId).map(CurrencyDefinition::enabled).orElse(false);
    }

    private CurrencyDefinition existing(String currencyId) throws Rejection {
        CurrencyDefinition c = registry.find(currencyId).orElseThrow(() -> new Rejection(FailureReason.UNKNOWN_CURRENCY));
        if (conflicted.contains(c.id())) {
            // Stored minor units cannot be interpreted with the configured scale: refuse reads too.
            throw new Rejection(FailureReason.CURRENCY_DISABLED);
        }
        return c;
    }

    private CurrencyDefinition usable(String currencyId) throws Rejection {
        CurrencyDefinition c = existing(currencyId);
        if (!c.enabled()) {
            throw new Rejection(FailureReason.CURRENCY_DISABLED);
        }
        return c;
    }

    // ================================================================== reads

    @Override
    public CompletableFuture<BigDecimal> balance(UUID player, String currencyId) {
        return read(() -> {
            requireNonNull(player);
            CurrencyDefinition c = existing(currencyId);
            return c.fromMinor(readMinor(player, c));
        });
    }

    @Override
    public CompletableFuture<Boolean> has(UUID player, String currencyId, BigDecimal amount) {
        return read(() -> {
            requireNonNull(player);
            CurrencyDefinition c = existing(currencyId);
            long minor = c.toMinor(amount, true);
            return readMinor(player, c) >= minor;
        });
    }

    private long readMinor(UUID player, CurrencyDefinition c) throws Exception {
        Long stored = db.transaction(conn -> repo.selectBalance(conn, player, c.id()));
        return stored == null ? c.startingMinor() : stored;
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> history(UUID player, String currencyId, int limit) {
        return read(() -> {
            requireNonNull(player);
            String filter = null;
            if (currencyId != null) {
                filter = existing(currencyId).id();
            }
            int n = Math.clamp(limit, 1, MAX_QUERY_LIMIT);
            String f = filter;
            List<LedgerEntry> rows = db.transaction(conn -> repo.history(conn, player, f, n));
            List<TransactionRecord> out = new ArrayList<>(rows.size());
            for (LedgerEntry e : rows) {
                out.add(toRecord(e));
            }
            return out;
        });
    }

    private TransactionRecord toRecord(LedgerEntry e) {
        int scale = registry.find(e.currencyId()).map(CurrencyDefinition::scale)
                .orElseGet(() -> storedScales.getOrDefault(e.currencyId(), 0));
        return new TransactionRecord(e.sequence(), e.transactionId(), e.idempotencyKey(), e.currencyId(), e.type(), e.actor(),
                e.account(), e.counterparty(), dec(e.amount(), scale), dec(e.balanceBefore(), scale), dec(e.balanceAfter(), scale),
                dec(e.counterpartyBalanceBefore(), scale), dec(e.counterpartyBalanceAfter(), scale), e.reason(), e.serverId(),
                Instant.ofEpochMilli(e.createdAt()));
    }

    private static BigDecimal dec(Long minor, int scale) {
        return minor == null ? null : CurrencyDefinition.fromMinor(minor, scale);
    }

    @Override
    public CompletableFuture<List<BalanceEntry>> top(String currencyId, int limit, int offset) {
        return read(() -> {
            CurrencyDefinition c = existing(currencyId);
            int n = Math.clamp(limit, 1, MAX_QUERY_LIMIT);
            int skip = Math.max(0, offset);
            List<CurrencyRepository.TopRow> rows = db.transaction(conn -> repo.top(conn, c.id(), n, skip));
            List<BalanceEntry> out = new ArrayList<>(rows.size());
            for (CurrencyRepository.TopRow r : rows) {
                out.add(new BalanceEntry(r.player(), r.name(), c.fromMinor(r.balance())));
            }
            return out;
        });
    }

    // ================================================================== internal (plugin-only) helpers

    /** Remembers a player's current name for offline lookups and leaderboards. */
    public CompletableFuture<Void> recordPlayerName(UUID player, String name) {
        return read(() -> {
            db.transaction(conn -> {
                repo.upsertPlayer(conn, player, name, System.currentTimeMillis());
                return null;
            });
            return null;
        });
    }

    public CompletableFuture<Optional<UUID>> findPlayerByName(String name) {
        return read(() -> Optional.ofNullable(db.transaction(conn -> repo.findPlayerByName(conn, name))));
    }

    /** Stored state of a currency id, whether or not it is configured right now. */
    public record CurrencyStats(String currencyId, Integer storedScale, long accounts, long ledgerEntries, BigDecimal totalSupply) {

        /** {@code true} when balances of this id were ever stored, so its scale is fixed. */
        public boolean registered() {
            return storedScale != null;
        }
    }

    /**
     * Reads the stored scale, account count, ledger size and total supply of a currency id straight
     * from the database. Works for ids that are no longer configured (their data is kept).
     */
    public CompletableFuture<CurrencyStats> stats(String currencyId) {
        String id = CurrencyRegistry.normalize(currencyId);
        return read(() -> {
            if (id == null || !CurrencyDefinition.ID_PATTERN.matcher(id).matches()) {
                throw new Rejection(FailureReason.UNKNOWN_CURRENCY);
            }
            return db.transaction(conn -> {
                Integer scale = repo.loadScales(conn).get(id);
                long accounts = repo.countAccounts(conn, id);
                long ledger = repo.countLedger(conn, id);
                BigDecimal supply = CurrencyDefinition.fromMinor(repo.totalSupply(conn, id), scale == null ? 0 : scale);
                return new CurrencyStats(id, scale, accounts, ledger, supply);
            });
        });
    }

    /**
     * Called after every mutation that may have changed balances: committed, or with an unknown commit
     * outcome. Rejected and rolled-back operations do not call it. Runs on a database thread; keep it cheap.
     * Plugin-internal (feeds the placeholder cache); not part of the public API.
     */
    public void setBalanceChangeListener(BiConsumer<UUID, String> listener) {
        this.balanceListener = listener;
    }

    private void notifyChanged(List<UUID> accounts, CurrencyDefinition c) {
        BiConsumer<UUID, String> listener = balanceListener;
        if (listener == null) {
            return;
        }
        for (UUID account : accounts) {
            try {
                listener.accept(account, c.id());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Balance change listener failed", e);
            }
        }
    }

    /** Sum of all stored balances of a currency (audit/test helper). */
    public CompletableFuture<BigDecimal> totalSupply(String currencyId) {
        return read(() -> {
            CurrencyDefinition c = existing(currencyId);
            return c.fromMinor(db.transaction(conn -> repo.totalSupply(conn, c.id())));
        });
    }

    // ================================================================== mutations

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID player, String currencyId, BigDecimal amount, TransactionContext context) {
        TransactionType type = TransactionType.DEPOSIT;
        return mutate(type, currencyId, amount, () -> {
            requireNonNull(player, context);
            CurrencyDefinition c = usable(currencyId);
            long minor = c.toMinor(amount, false);
            return execute(type, c, amount, context, List.of(player), (conn, txId, now) -> {
                long before = lock(conn, player, c);
                if (minor > c.maxMinor() - before) {
                    throw new Rejection(FailureReason.BALANCE_LIMIT_EXCEEDED);
                }
                long after = before + minor;
                repo.casBalance(conn, player, c.id(), before, after, now);
                repo.insertLedger(conn, new LedgerEntry(0, txId, context.idempotencyKey(), c.id(), type, context.actor(),
                        player, null, minor, before, after, null, null, context.reason(), serverId, now));
                return TransactionResult.ok(txId, type, c.id(), amount, c.fromMinor(after), null);
            });
        });
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID player, String currencyId, BigDecimal amount, TransactionContext context) {
        TransactionType type = TransactionType.WITHDRAW;
        return mutate(type, currencyId, amount, () -> {
            requireNonNull(player, context);
            CurrencyDefinition c = usable(currencyId);
            long minor = c.toMinor(amount, false);
            return execute(type, c, amount, context, List.of(player), (conn, txId, now) -> {
                long before = lock(conn, player, c);
                if (before < minor) {
                    throw new Rejection(FailureReason.INSUFFICIENT_FUNDS);
                }
                long after = before - minor;
                repo.casBalance(conn, player, c.id(), before, after, now);
                repo.insertLedger(conn, new LedgerEntry(0, txId, context.idempotencyKey(), c.id(), type, context.actor(),
                        player, null, minor, before, after, null, null, context.reason(), serverId, now));
                return TransactionResult.ok(txId, type, c.id(), amount, c.fromMinor(after), null);
            });
        });
    }

    @Override
    public CompletableFuture<TransactionResult> set(UUID player, String currencyId, BigDecimal amount, TransactionContext context) {
        TransactionType type = TransactionType.SET;
        return mutate(type, currencyId, amount, () -> {
            requireNonNull(player, context);
            CurrencyDefinition c = usable(currencyId);
            long minor = c.toMinor(amount, true);
            return execute(type, c, amount, context, List.of(player), (conn, txId, now) -> {
                long before = lock(conn, player, c);
                repo.casBalance(conn, player, c.id(), before, minor, now);
                // For SET the ledger amount is the new balance; before/after carry the delta.
                repo.insertLedger(conn, new LedgerEntry(0, txId, context.idempotencyKey(), c.id(), type, context.actor(),
                        player, null, minor, before, minor, null, null, context.reason(), serverId, now));
                return TransactionResult.ok(txId, type, c.id(), amount, c.fromMinor(minor), null);
            });
        });
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, String currencyId, BigDecimal amount, TransactionContext context) {
        TransactionType type = TransactionType.TRANSFER;
        return mutate(type, currencyId, amount, () -> {
            requireNonNull(from, to, context);
            CurrencyDefinition c = usable(currencyId);
            long minor = c.toMinor(amount, false);
            if (from.equals(to)) {
                throw new Rejection(FailureReason.SELF_TRANSFER);
            }
            // The one authoritative transfer switch. There is deliberately no override parameter.
            if (!c.transferEnabled()) {
                throw new Rejection(FailureReason.TRANSFER_DISABLED);
            }
            return execute(type, c, amount, context, List.of(from, to), (conn, txId, now) -> {
                // Lock both rows in a global order (UUID string) so opposite transfers cannot deadlock.
                long fromBefore;
                long toBefore;
                if (from.toString().compareTo(to.toString()) < 0) {
                    fromBefore = lock(conn, from, c);
                    toBefore = lock(conn, to, c);
                } else {
                    toBefore = lock(conn, to, c);
                    fromBefore = lock(conn, from, c);
                }
                if (fromBefore < minor) {
                    throw new Rejection(FailureReason.INSUFFICIENT_FUNDS);
                }
                if (minor > c.maxMinor() - toBefore) {
                    throw new Rejection(FailureReason.BALANCE_LIMIT_EXCEEDED);
                }
                long fromAfter = fromBefore - minor;
                long toAfter = toBefore + minor;
                repo.casBalance(conn, from, c.id(), fromBefore, fromAfter, now);
                repo.casBalance(conn, to, c.id(), toBefore, toAfter, now);
                repo.insertLedger(conn, new LedgerEntry(0, txId, context.idempotencyKey(), c.id(), type, context.actor(),
                        from, to, minor, fromBefore, fromAfter, toBefore, toAfter, context.reason(), serverId, now));
                return TransactionResult.ok(txId, type, c.id(), amount, c.fromMinor(fromAfter), c.fromMinor(toAfter));
            });
        });
    }

    // ================================================================== transaction plumbing

    @FunctionalInterface
    private interface Body {
        TransactionResult run(Connection connection, String transactionId, long now) throws SQLException, Rejection;
    }

    @FunctionalInterface
    private interface Task<T> {
        T call() throws Exception;
    }

    /** Signals that an account row expected to exist is missing (stale cache hint). Not transient. */
    private static final class AccountMissingException extends SQLException {
        private static final long serialVersionUID = 1L;

        AccountMissingException(String message) {
            super(message);
        }
    }

    private long lock(Connection conn, UUID player, CurrencyDefinition c) throws SQLException {
        Long balance = repo.lockBalance(conn, player, c.id());
        if (balance == null) {
            throw new AccountMissingException(player + "/" + c.id());
        }
        return balance;
    }

    private TransactionResult execute(TransactionType type, CurrencyDefinition c, BigDecimal amount, TransactionContext context,
                                      List<UUID> accounts, Body body) {
        String key = context.idempotencyKey();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                for (UUID account : accounts) {
                    ensureAccount(account, c);
                }
                String txId = UUID.randomUUID().toString();
                TransactionResult result = db.transaction(conn -> {
                    if (key != null) {
                        String existing = repo.findTransactionIdByKey(conn, key);
                        if (existing != null) {
                            throw new Rejection(FailureReason.DUPLICATE_TRANSACTION, existing);
                        }
                    }
                    return body.run(conn, txId, System.currentTimeMillis());
                });
                notifyChanged(accounts, c);
                return result;
            } catch (Rejection r) {
                if (r.reason() == FailureReason.DUPLICATE_TRANSACTION) {
                    return TransactionResult.duplicate(r.transactionId(), type, c.id(), amount);
                }
                return TransactionResult.failed(r.reason(), type, c.id(), amount);
            } catch (AccountMissingException e) {
                for (UUID account : accounts) {
                    knownAccounts.remove(accountKey(account, c));
                }
            } catch (Database.CommitUnknownException e) {
                logger.log(Level.SEVERE, "Commit of a " + type + " on '" + c.id() + "' failed; its outcome is unknown", e.getCause());
                notifyChanged(accounts, c);
                return TransactionResult.failed(FailureReason.OUTCOME_UNKNOWN, type, c.id(), amount);
            } catch (SQLException e) {
                if (key != null && db.isUniqueViolation(e)) {
                    String existing = lookupKey(key);
                    if (existing != null) {
                        return TransactionResult.duplicate(existing, type, c.id(), amount);
                    }
                }
                logger.log(Level.WARNING, "Database error during " + type + " on '" + c.id() + "'; rolled back", e);
                return TransactionResult.failed(FailureReason.STORAGE_ERROR, type, c.id(), amount);
            }
        }
        logger.warning("Account rows vanished during a " + type + " on '" + c.id() + "'; refused");
        return TransactionResult.failed(FailureReason.STORAGE_ERROR, type, c.id(), amount);
    }

    private String lookupKey(String key) {
        try {
            return db.transaction(conn -> repo.findTransactionIdByKey(conn, key));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates the account with the starting balance (and an INITIAL ledger entry) in its own small
     * transaction. Two creators racing is harmless: the loser hits the primary key and moves on.
     */
    private void ensureAccount(UUID player, CurrencyDefinition c) throws SQLException {
        String k = accountKey(player, c);
        if (knownAccounts.contains(k)) {
            return;
        }
        try {
            db.transaction(conn -> {
                if (repo.selectBalance(conn, player, c.id()) != null) {
                    return null;
                }
                long now = System.currentTimeMillis();
                repo.insertAccount(conn, player, c.id(), c.startingMinor(), now);
                if (c.startingMinor() > 0) {
                    repo.insertLedger(conn, new LedgerEntry(0, UUID.randomUUID().toString(), null, c.id(), TransactionType.INITIAL,
                            STARTING_BALANCE_ACTOR, player, null, c.startingMinor(), null, c.startingMinor(), null, null,
                            "starting balance", serverId, now));
                }
                return null;
            });
        } catch (SQLException e) {
            if (!db.isUniqueViolation(e)) {
                throw e;
            }
        } catch (Database.CommitUnknownException e) {
            throw new SQLException("account creation outcome unknown", e.getCause());
        } catch (Rejection impossible) {
            throw new IllegalStateException(impossible);
        }
        if (knownAccounts.size() > KNOWN_ACCOUNT_CACHE_LIMIT) {
            knownAccounts.clear();
        }
        knownAccounts.add(k);
    }

    private static String accountKey(UUID player, CurrencyDefinition c) {
        return player + ":" + c.id();
    }

    private CompletableFuture<TransactionResult> mutate(TransactionType type, String currencyId, BigDecimal amount,
                                                        Task<TransactionResult> task) {
        String id = CurrencyRegistry.normalize(currencyId);
        CompletableFuture<TransactionResult> out = new CompletableFuture<>();
        ready.whenComplete((v, err) -> {
            if (err != null || closed) {
                out.complete(TransactionResult.failed(FailureReason.SERVICE_UNAVAILABLE, type, id, amount));
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        out.complete(task.call());
                    } catch (Rejection r) {
                        out.complete(TransactionResult.failed(r.reason(), type, id, amount));
                    } catch (Throwable t) {
                        logger.log(Level.SEVERE, "Unexpected error during " + type + "; nothing was committed", t);
                        out.complete(TransactionResult.failed(FailureReason.STORAGE_ERROR, type, id, amount));
                    }
                });
            } catch (RejectedExecutionException e) {
                out.complete(TransactionResult.failed(FailureReason.SERVICE_UNAVAILABLE, type, id, amount));
            }
        });
        return out;
    }

    private <T> CompletableFuture<T> read(Task<T> task) {
        CompletableFuture<T> out = new CompletableFuture<>();
        ready.whenComplete((v, err) -> {
            if (err != null || closed) {
                out.completeExceptionally(new CurrencyException(FailureReason.SERVICE_UNAVAILABLE, "MultiCurrency is not available"));
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        out.complete(task.call());
                    } catch (Rejection r) {
                        out.completeExceptionally(new CurrencyException(r.reason(), r.reason().name()));
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Database read failed", t);
                        out.completeExceptionally(new CurrencyException(FailureReason.STORAGE_ERROR, "database error", t));
                    }
                });
            } catch (RejectedExecutionException e) {
                out.completeExceptionally(new CurrencyException(FailureReason.SERVICE_UNAVAILABLE, "MultiCurrency is not available"));
            }
        });
        return out;
    }

    private static void requireNonNull(Object... values) throws Rejection {
        for (Object v : values) {
            if (v == null) {
                throw new Rejection(FailureReason.INVALID_ARGUMENT);
            }
        }
    }
}
