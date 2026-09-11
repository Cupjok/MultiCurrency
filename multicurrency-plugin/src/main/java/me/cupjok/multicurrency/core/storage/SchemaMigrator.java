package me.cupjok.multicurrency.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Versioned schema migrations.
 *
 * <p>Each migration is idempotent ({@code CREATE ... IF NOT EXISTS}) because MariaDB/MySQL DDL commits
 * implicitly and cannot be rolled back. On MariaDB a named lock ({@code GET_LOCK}) serialises servers
 * that start at the same time. A database written by a newer plugin version is refused.
 */
public final class SchemaMigrator {

    public static final int LATEST_VERSION = 1;

    private final Database db;
    private final Logger logger;

    public SchemaMigrator(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void migrate() throws Exception {
        db.transaction(c -> {
            boolean locked = acquireLock(c);
            try {
                exec(c, "CREATE TABLE IF NOT EXISTS " + db.table("schema_version")
                        + " (version INT NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL)" + db.dialect().tableOptions());
                int current = currentVersion(c);
                if (current > LATEST_VERSION) {
                    throw new SQLException("Database schema version " + current + " is newer than this plugin supports ("
                            + LATEST_VERSION + "). Refusing to start; update MultiCurrency.");
                }
                for (int v = current + 1; v <= LATEST_VERSION; v++) {
                    for (String statement : statements(v)) {
                        exec(c, statement);
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO " + db.table("schema_version") + " (version, applied_at) VALUES (?, ?)")) {
                        ps.setInt(1, v);
                        ps.setLong(2, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    if (logger != null) {
                        logger.info("Applied database schema version " + v);
                    }
                }
                return null;
            } finally {
                if (locked) {
                    releaseLock(c);
                }
            }
        });
    }

    private int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + db.table("schema_version"))) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private boolean acquireLock(Connection c) throws SQLException {
        if (db.dialect() != Dialect.MARIADB) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 60)")) {
            ps.setString(1, lockName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new SQLException("Could not acquire the schema migration lock within 60 seconds");
                }
            }
        }
        return true;
    }

    private void releaseLock(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName());
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // Released when the session ends anyway.
        }
    }

    private String lockName() {
        return "multicurrency_migrate_" + db.table("");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private List<String> statements(int version) {
        Dialect d = db.dialect();
        boolean maria = d == Dialect.MARIADB;
        List<String> out = new ArrayList<>();
        switch (version) {
            case 1 -> {
                out.add("CREATE TABLE IF NOT EXISTS " + db.table("currencies") + " ("
                        + "currency_id VARCHAR(32) NOT NULL PRIMARY KEY,"
                        + "scale INT NOT NULL,"
                        + "created_at BIGINT NOT NULL)" + d.tableOptions());

                out.add("CREATE TABLE IF NOT EXISTS " + db.table("balances") + " ("
                        + "player_uuid CHAR(36) NOT NULL,"
                        + "currency_id VARCHAR(32) NOT NULL,"
                        + "balance BIGINT NOT NULL,"
                        + "updated_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (player_uuid, currency_id),"
                        + (maria ? "INDEX " + db.table("idx_bal_top") + " (currency_id, balance)," : "")
                        + "CONSTRAINT " + db.table("chk_bal_nonneg") + " CHECK (balance >= 0))" + d.tableOptions());

                out.add("CREATE TABLE IF NOT EXISTS " + db.table("transactions") + " ("
                        + "seq " + d.autoIncrementPrimaryKey() + ","
                        + "tx_id CHAR(36) NOT NULL,"
                        + "idempotency_key VARCHAR(128) NULL,"
                        + "currency_id VARCHAR(32) NOT NULL,"
                        + "tx_type VARCHAR(16) NOT NULL,"
                        + "actor_type VARCHAR(16) NOT NULL,"
                        + "actor_id VARCHAR(64) NOT NULL,"
                        + "actor_name VARCHAR(64) NOT NULL,"
                        + "account_uuid CHAR(36) NOT NULL,"
                        + "counterparty_uuid CHAR(36) NULL,"
                        + "amount BIGINT NOT NULL,"
                        + "balance_before BIGINT NULL,"
                        + "balance_after BIGINT NOT NULL,"
                        + "cp_balance_before BIGINT NULL,"
                        + "cp_balance_after BIGINT NULL,"
                        + "reason VARCHAR(255) NULL,"
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "CONSTRAINT " + db.table("uq_tx_id") + " UNIQUE (tx_id),"
                        + "CONSTRAINT " + db.table("uq_tx_key") + " UNIQUE (idempotency_key)"
                        + (maria ? ",INDEX " + db.table("idx_tx_account") + " (account_uuid, seq)"
                        + ",INDEX " + db.table("idx_tx_cp") + " (counterparty_uuid, seq)"
                        + ",INDEX " + db.table("idx_tx_currency") + " (currency_id, seq)" : "")
                        + ")" + d.tableOptions());

                out.add("CREATE TABLE IF NOT EXISTS " + db.table("players") + " ("
                        + "player_uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "name_lower VARCHAR(16) NOT NULL,"
                        + "updated_at BIGINT NOT NULL"
                        + (maria ? ",INDEX " + db.table("idx_players_name") + " (name_lower)" : "")
                        + ")" + d.tableOptions());

                if (!maria) {
                    out.add("CREATE INDEX IF NOT EXISTS " + db.table("idx_bal_top") + " ON " + db.table("balances") + " (currency_id, balance)");
                    out.add("CREATE INDEX IF NOT EXISTS " + db.table("idx_tx_account") + " ON " + db.table("transactions") + " (account_uuid, seq)");
                    out.add("CREATE INDEX IF NOT EXISTS " + db.table("idx_tx_cp") + " ON " + db.table("transactions") + " (counterparty_uuid, seq)");
                    out.add("CREATE INDEX IF NOT EXISTS " + db.table("idx_tx_currency") + " ON " + db.table("transactions") + " (currency_id, seq)");
                    out.add("CREATE INDEX IF NOT EXISTS " + db.table("idx_players_name") + " ON " + db.table("players") + " (name_lower)");
                }
            }
            default -> throw new IllegalStateException("no migration for version " + version);
        }
        return out;
    }
}
