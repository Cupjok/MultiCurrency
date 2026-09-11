package me.cupjok.multicurrency.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.cupjok.multicurrency.core.Rejection;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Connection pool plus the transaction layer.
 *
 * <p>{@link #transaction} is the only way the service touches the database. It runs the body in one
 * JDBC transaction, commits, and rolls back on any exception. Deadlocks, lock-wait timeouts and SQLite
 * busy errors are retried from scratch (the body must therefore be re-runnable, which it is: it only
 * talks to the database). A failure inside {@code commit()} is reported as {@link CommitUnknownException}
 * because the transaction may or may not have been applied.
 */
public final class Database implements AutoCloseable {

    public static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{0,24}");

    private static final int MAX_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final AutoCloseable closer;
    private final Dialect dialect;
    private final String prefix;
    private final BooleanSupplier onMainThread;
    private final Logger logger;

    public Database(DataSource dataSource, AutoCloseable closer, Dialect dialect, String prefix,
                    BooleanSupplier onMainThread, Logger logger) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.closer = closer;
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("table prefix must match " + PREFIX_PATTERN.pattern());
        }
        this.prefix = prefix;
        this.onMainThread = onMainThread == null ? () -> false : onMainThread;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ factories

    public static Database sqlite(Path file, String prefix, BooleanSupplier onMainThread, Logger logger) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("MultiCurrency-SQLite");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        // One connection: SQLite has a single writer anyway, and this serialises every transaction
        // inside the JVM so no SQLITE_BUSY storms occur.
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setInitializationFailTimeout(-1);
        cfg.addDataSourceProperty("journal_mode", "WAL");
        // FULL: a committed transaction survives power loss, not only a process crash.
        cfg.addDataSourceProperty("synchronous", "FULL");
        cfg.addDataSourceProperty("busy_timeout", "10000");
        cfg.addDataSourceProperty("foreign_keys", "true");
        cfg.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        HikariDataSource ds = new HikariDataSource(cfg);
        return new Database(ds, ds, Dialect.SQLITE, prefix, onMainThread, logger);
    }

    public static Database mariadb(String host, int port, String database, String user, String password,
                                   int poolSize, Map<String, String> properties, String prefix,
                                   BooleanSupplier onMainThread, Logger logger) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("MultiCurrency-MariaDB");
        cfg.setDriverClassName("org.mariadb.jdbc.Driver");
        cfg.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(Math.max(1, poolSize));
        cfg.setMinimumIdle(Math.min(2, Math.max(1, poolSize)));
        cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        cfg.setAutoCommit(true);
        cfg.setConnectionTimeout(10_000);
        cfg.setMaxLifetime(1_800_000);
        // Never connect from the constructor: it runs on the server main thread.
        cfg.setInitializationFailTimeout(-1);
        if (properties != null) {
            properties.forEach(cfg::addDataSourceProperty);
        }
        HikariDataSource ds = new HikariDataSource(cfg);
        return new Database(ds, ds, Dialect.MARIADB, prefix, onMainThread, logger);
    }

    // ------------------------------------------------------------------ transactions

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException, Rejection;
    }

    /** Thrown when {@code commit()} itself failed: the outcome is unknown. */
    public static final class CommitUnknownException extends Exception {
        private static final long serialVersionUID = 1L;

        CommitUnknownException(SQLException cause) {
            super("commit failed; outcome unknown", cause);
        }
    }

    /** Raised by a body when a guarded write matched no row although its row lock was held. Retried. */
    public static final class ConcurrentUpdateException extends SQLException {
        private static final long serialVersionUID = 1L;

        public ConcurrentUpdateException(String message) {
            super(message, "40001");
        }
    }

    public <T> T transaction(Work<T> work) throws SQLException, Rejection, CommitUnknownException {
        if (onMainThread.getAsBoolean()) {
            throw new IllegalStateException("database access on the server main thread");
        }
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result;
                    try {
                        result = work.run(connection);
                    } catch (SQLException | Rejection | RuntimeException | Error e) {
                        rollbackQuietly(connection);
                        throw e;
                    }
                    try {
                        connection.commit();
                    } catch (SQLException e) {
                        rollbackQuietly(connection);
                        throw new CommitUnknownException(e);
                    }
                    return result;
                } finally {
                    restoreAutoCommit(connection, autoCommit);
                }
            } catch (SQLException e) {
                if (!isTransient(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                if (logger != null) {
                    logger.fine("Retrying transaction after transient error (attempt " + attempt + "): " + e.getMessage());
                }
                backoff(attempt);
            }
        }
        throw last;
    }

    private boolean isTransient(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                if ("40001".equals(s.getSQLState())) {
                    return true;
                }
                int code = s.getErrorCode();
                if (dialect == Dialect.MARIADB && (code == 1213 || code == 1205)) {
                    return true;
                }
                // SQLITE_BUSY (5) / SQLITE_LOCKED (6), possibly as extended result codes.
                if (dialect == Dialect.SQLITE && ((code & 0xFF) == 5 || (code & 0xFF) == 6)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Unique/primary key constraint violation. */
    public boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                String state = s.getSQLState();
                if (dialect == Dialect.MARIADB && (s.getErrorCode() == 1062 || s.getErrorCode() == 1586)) {
                    return true;
                }
                if (dialect == Dialect.SQLITE && (s.getErrorCode() & 0xFF) == 19) {
                    String msg = String.valueOf(s.getMessage());
                    return msg.contains("UNIQUE") || msg.contains("PRIMARY KEY");
                }
                if (dialect == Dialect.MARIADB && state != null && state.startsWith("23") && s.getErrorCode() != 4025) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(200, 10L * attempt * attempt) + (long) (Math.random() * 10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Rollback failed; the pooled connection will be discarded", e);
            }
            // Evict the connection rather than hand a half-open transaction to the next user.
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException ignored) {
                // Nothing more can be done.
            }
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            if (!connection.isClosed()) {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ignored) {
            // Connection is broken; the pool will validate it.
        }
    }

    // ------------------------------------------------------------------ accessors

    public Dialect dialect() {
        return dialect;
    }

    /** Replaces {@code {p}} with the configured table prefix. */
    public String sql(String template) {
        return template.replace("{p}", prefix);
    }

    public String table(String name) {
        return prefix + name;
    }

    @Override
    public void close() {
        if (closer != null) {
            try {
                closer.close();
            } catch (Exception e) {
                if (logger != null) {
                    logger.log(Level.WARNING, "Failed to close the connection pool", e);
                }
            }
        }
    }
}
