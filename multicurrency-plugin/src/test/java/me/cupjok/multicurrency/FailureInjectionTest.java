package me.cupjok.multicurrency;

import me.cupjok.multicurrency.api.CurrencyException;
import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.api.TransactionResult;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import me.cupjok.multicurrency.core.storage.Dialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static me.cupjok.multicurrency.TestSupport.ctx;
import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database failures at every step of a transaction. Each must leave balances exactly as before
 * (or, for a commit failure, report the outcome as unknown).
 */
class FailureInjectionTest {

    @TempDir
    Path dir;

    private FaultyDataSource faulty;
    private CurrencyService service;

    /** Wraps a real SQLite data source and fails chosen statements on demand. */
    static final class FaultyDataSource implements DataSource {
        final DataSource delegate;
        volatile String failSqlContaining;
        final AtomicInteger skipBeforeFailure = new AtomicInteger();
        volatile String failSqlState = "HY000";
        volatile int failuresLeft;
        volatile boolean failCommit;
        volatile boolean down;

        FaultyDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void failOn(String sqlFragment, int skip, int times, String sqlState) {
            skipBeforeFailure.set(skip);
            failSqlState = sqlState;
            failuresLeft = times;
            failSqlContaining = sqlFragment;
        }

        void reset() {
            failSqlContaining = null;
            failCommit = false;
            down = false;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLException("database is down", "08001");
            }
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commit") && failCommit) {
                            throw new SQLException("connection lost during commit", "08S01");
                        }
                        Object result = invoke(real, method, args);
                        if (result instanceof PreparedStatement ps && method.getName().equals("prepareStatement")) {
                            return wrap(ps, (String) args[0]);
                        }
                        return result;
                    });
        }

        private PreparedStatement wrap(PreparedStatement real, String sql) {
            InvocationHandler h = (proxy, method, args) -> {
                String fragment = failSqlContaining;
                if (fragment != null && method.getName().startsWith("execute") && sql.contains(fragment) && failuresLeft > 0) {
                    if (skipBeforeFailure.getAndDecrement() <= 0) {
                        failuresLeft--;
                        throw new SQLException("injected failure", failSqlState);
                    }
                }
                return invoke(real, method, args);
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + dir.resolve("faulty.db"));
        faulty = new FaultyDataSource(sqlite);
        Database db = new Database(faulty, null, Dialect.SQLITE, "mc_", null, TestSupport.LOG);
        service = new CurrencyService(db, "test", 1, TestSupport.LOG);
        service.start(TestSupport.registry()).join();
    }

    @AfterEach
    void tearDown() {
        faulty.reset();
        service.close();
    }

    private BigDecimalPair balances(UUID a, UUID b) {
        return new BigDecimalPair(service.balance(a, "coins").join().toPlainString(), service.balance(b, "coins").join().toPlainString());
    }

    private record BigDecimalPair(String a, String b) {
    }

    @Test
    void failureOnRecipientCreditRollsBackSenderDebit() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        service.deposit(b, "coins", d("1"), ctx()).join();
        // Transfer issues two balance UPDATEs: let the first (one side) succeed, fail the second.
        faulty.failOn("UPDATE mc_balances", 1, 1, "HY000");
        TransactionResult r = service.transfer(a, b, "coins", d("4"), ctx()).join();
        assertFalse(r.success());
        assertEquals(FailureReason.STORAGE_ERROR, r.failureReason());
        faulty.reset();
        assertEquals(new BigDecimalPair("10.00", "1.00"), balances(a, b));
        assertEquals(2, service.history(a, "coins", 10).join().size() + service.history(b, "coins", 10).join().size());
    }

    @Test
    void failureWritingLedgerRollsBackBalances() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        service.deposit(b, "coins", d("1"), ctx()).join();
        faulty.failOn("INSERT INTO mc_transactions", 0, 1, "HY000");
        assertEquals(FailureReason.STORAGE_ERROR, service.transfer(a, b, "coins", d("4"), ctx()).join().failureReason());
        faulty.failOn("INSERT INTO mc_transactions", 0, 1, "HY000");
        assertEquals(FailureReason.STORAGE_ERROR, service.withdraw(a, "coins", d("4"), ctx()).join().failureReason());
        faulty.reset();
        assertEquals(new BigDecimalPair("10.00", "1.00"), balances(a, b));
    }

    @Test
    void commitFailureIsReportedAsUnknownNotSuccess() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        service.deposit(b, "coins", d("0.01"), ctx()).join();
        faulty.failCommit = true;
        TransactionResult r = service.transfer(a, b, "coins", d("4"), ctx("pay-7")).join();
        assertEquals(FailureReason.OUTCOME_UNKNOWN, r.failureReason());
        faulty.reset();
        // Here the commit really did not happen (the proxy threw before committing): nothing applied,
        // and retrying with the same key applies exactly once.
        assertEquals(new BigDecimalPair("10.00", "0.01"), balances(a, b));
        assertTrue(service.transfer(a, b, "coins", d("4"), ctx("pay-7")).join().success());
        assertEquals(FailureReason.DUPLICATE_TRANSACTION, service.transfer(a, b, "coins", d("4"), ctx("pay-7")).join().failureReason());
        assertEquals(new BigDecimalPair("6.00", "4.01"), balances(a, b));
    }

    @Test
    void changeListenerHearsAboutUnknownOutcomesButNotRollbacks() {
        UUID a = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        service.setBalanceChangeListener((player, currency) -> seen.add(currency));

        faulty.failOn("INSERT INTO mc_transactions", 0, 1, "HY000");
        assertEquals(FailureReason.STORAGE_ERROR, service.withdraw(a, "coins", d("1"), ctx()).join().failureReason());
        assertTrue(seen.isEmpty(), "a rolled-back change is not reported");

        faulty.reset();
        faulty.failCommit = true;
        assertEquals(FailureReason.OUTCOME_UNKNOWN, service.withdraw(a, "coins", d("1"), ctx()).join().failureReason());
        assertEquals(List.of("coins"), seen, "an unknown outcome may have changed the balance: caches must refresh");
    }

    @Test
    void transientErrorsAreRetriedAndAppliedOnce() {
        UUID a = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        faulty.failOn("INSERT INTO mc_transactions", 0, 2, "40001");
        TransactionResult r = service.withdraw(a, "coins", d("3"), ctx()).join();
        assertTrue(r.success(), String.valueOf(r.failureReason()));
        faulty.reset();
        assertEquals(d("7.00"), service.balance(a, "coins").join());
        assertEquals(2, service.history(a, "coins", 10).join().size());
    }

    @Test
    void databaseDownFailsClosed() {
        UUID a = UUID.randomUUID();
        service.deposit(a, "coins", d("10"), ctx()).join();
        faulty.down = true;
        assertEquals(FailureReason.STORAGE_ERROR, service.deposit(a, "coins", d("1"), ctx()).join().failureReason());
        assertEquals(FailureReason.STORAGE_ERROR, service.transfer(a, UUID.randomUUID(), "coins", d("1"), ctx()).join().failureReason());
        CompletionException e = assertThrows(CompletionException.class, () -> service.balance(a, "coins").join());
        assertEquals(FailureReason.STORAGE_ERROR, ((CurrencyException) e.getCause()).reason());
        faulty.reset();
        assertEquals(d("10.00"), service.balance(a, "coins").join());
    }

    @Test
    void failureCreatingAccountAppliesNothing() {
        UUID fresh = UUID.randomUUID();
        faulty.failOn("INSERT INTO mc_balances", 0, 1, "HY000");
        assertEquals(FailureReason.STORAGE_ERROR, service.deposit(fresh, "starter", d("5"), ctx()).join().failureReason());
        faulty.reset();
        assertEquals(d("50"), service.balance(fresh, "starter").join());
        assertTrue(service.history(fresh, null, 10).join().isEmpty());
        assertTrue(service.deposit(fresh, "starter", d("5"), ctx()).join().success());
        assertEquals(d("55"), service.balance(fresh, "starter").join());
    }

    @Test
    void mainThreadAccessIsRefused() {
        Database db = new Database(faulty, null, Dialect.SQLITE, "mc_", () -> true, TestSupport.LOG);
        assertThrows(IllegalStateException.class, () -> db.transaction(c -> null));
    }

    @Test
    void scaleChangeDisablesCurrencyInsteadOfCorruptingBalances() throws Exception {
        UUID a = UUID.randomUUID();
        service.deposit(a, "coins", d("12.34"), ctx()).join();
        service.close();

        Database db = new Database(faulty, null, Dialect.SQLITE, "mc_", null, TestSupport.LOG);
        CurrencyRegistry changed = new CurrencyRegistry(List.of(
                new CurrencyDefinition("coins", "Coins", "C", true, 0, d("0"), null, true, null)));
        service = new CurrencyService(db, "test", 1, TestSupport.LOG);
        service.start(changed).join();
        assertTrue(service.conflictedCurrencies().contains("coins"));
        assertFalse(service.isEnabled("coins"));
        assertEquals(FailureReason.CURRENCY_DISABLED, service.deposit(a, "coins", d("1"), ctx()).join().failureReason());
        CompletionException e = assertThrows(CompletionException.class, () -> service.balance(a, "coins").join());
        assertEquals(FailureReason.CURRENCY_DISABLED, ((CurrencyException) e.getCause()).reason());

        // Restoring the original scale restores the exact balance.
        service.reload(TestSupport.registry()).join();
        assertEquals(d("12.34"), service.balance(a, "coins").join());
    }

    @Test
    void newerSchemaVersionIsRefused() throws Exception {
        service.close();
        try (Connection c = faulty.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO mc_schema_version (version, applied_at) VALUES (99, 0)");
        }
        Database db = new Database(faulty, null, Dialect.SQLITE, "mc_", null, TestSupport.LOG);
        service = new CurrencyService(db, "test", 1, TestSupport.LOG);
        assertThrows(CompletionException.class, () -> service.start(TestSupport.registry()).join());
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, service.deposit(UUID.randomUUID(), "coins", d("1"), ctx()).join().failureReason());
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        service.close();
        Database db = new Database(faulty, null, Dialect.SQLITE, "mc_", null, TestSupport.LOG);
        service = new CurrencyService(db, "test", 1, TestSupport.LOG);
        service.start(TestSupport.registry()).join();
        assertEquals(1, TestSupport.scalar(db, "SELECT COUNT(*) FROM {p}schema_version"));
    }

    @Test
    void databaseRejectsNegativeBalancesEvenIfCodeRegressed() {
        UUID a = UUID.randomUUID();
        service.deposit(a, "coins", d("1"), ctx()).join();
        SQLException e = assertThrows(SQLException.class, () -> {
            try (Connection c = faulty.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE mc_balances SET balance = -1 WHERE player_uuid = '" + a + "'");
            }
        });
        assertTrue(e.getMessage().contains("CHECK"), e.getMessage());
    }
}
