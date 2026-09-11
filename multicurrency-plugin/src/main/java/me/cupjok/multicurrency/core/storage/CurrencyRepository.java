package me.cupjok.multicurrency.core.storage;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.TransactionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * All SQL lives here. Every method takes the caller's {@link Connection}, so the service decides the
 * transaction boundaries (see {@link Database#transaction}). Nothing here commits.
 */
public final class CurrencyRepository {

    public record TopRow(UUID player, String name, long balance) {
    }

    private final Database db;
    private final String balances;
    private final String transactions;
    private final String players;
    private final String currencies;

    public CurrencyRepository(Database db) {
        this.db = db;
        this.balances = db.table("balances");
        this.transactions = db.table("transactions");
        this.players = db.table("players");
        this.currencies = db.table("currencies");
    }

    // ------------------------------------------------------------------ balances

    /** Plain read, no lock. {@code null} when the account does not exist. */
    public Long selectBalance(Connection c, UUID player, String currencyId) throws SQLException {
        return balanceQuery(c, player, currencyId, "");
    }

    /**
     * Reads and row-locks the balance for the rest of the transaction ({@code FOR UPDATE} on MariaDB;
     * SQLite holds its database write lock because transactions begin IMMEDIATE).
     */
    public Long lockBalance(Connection c, UUID player, String currencyId) throws SQLException {
        return balanceQuery(c, player, currencyId, db.dialect().forUpdate());
    }

    private Long balanceQuery(Connection c, UUID player, String currencyId, String suffix) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM " + balances + " WHERE player_uuid = ? AND currency_id = ?" + suffix)) {
            ps.setString(1, player.toString());
            ps.setString(2, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public void insertAccount(Connection c, UUID player, String currencyId, long balance, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + balances + " (player_uuid, currency_id, balance, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, currencyId);
            ps.setLong(3, balance);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /**
     * Compare-and-set write. Applies only if the stored balance still equals {@code expected}, so even
     * without a row lock a concurrent writer can never be overwritten. Matching no row means the
     * locking assumption was violated: the transaction is aborted and retried.
     */
    public void casBalance(Connection c, UUID player, String currencyId, long expected, long value, long now) throws SQLException {
        if (value < 0) {
            throw new IllegalStateException("negative balance computed for " + player + "/" + currencyId);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + balances + " SET balance = ?, updated_at = ? WHERE player_uuid = ? AND currency_id = ? AND balance = ?")) {
            ps.setLong(1, value);
            ps.setLong(2, now);
            ps.setString(3, player.toString());
            ps.setString(4, currencyId);
            ps.setLong(5, expected);
            if (ps.executeUpdate() != 1) {
                throw new Database.ConcurrentUpdateException("balance of " + player + "/" + currencyId + " changed concurrently");
            }
        }
    }

    // ------------------------------------------------------------------ ledger

    public String findTransactionIdByKey(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT tx_id FROM " + transactions + " WHERE idempotency_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void insertLedger(Connection c, LedgerEntry e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + transactions
                + " (tx_id, idempotency_key, currency_id, tx_type, actor_type, actor_id, actor_name, account_uuid,"
                + " counterparty_uuid, amount, balance_before, balance_after, cp_balance_before, cp_balance_after,"
                + " reason, server_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, e.transactionId());
            setNullableString(ps, 2, e.idempotencyKey());
            ps.setString(3, e.currencyId());
            ps.setString(4, e.type().name());
            ps.setString(5, e.actor().type().name());
            ps.setString(6, e.actor().id());
            ps.setString(7, e.actor().name());
            ps.setString(8, e.account().toString());
            setNullableString(ps, 9, e.counterparty() == null ? null : e.counterparty().toString());
            ps.setLong(10, e.amount());
            setNullableLong(ps, 11, e.balanceBefore());
            ps.setLong(12, e.balanceAfter());
            setNullableLong(ps, 13, e.counterpartyBalanceBefore());
            setNullableLong(ps, 14, e.counterpartyBalanceAfter());
            setNullableString(ps, 15, e.reason());
            ps.setString(16, e.serverId());
            ps.setLong(17, e.createdAt());
            ps.executeUpdate();
        }
    }

    public List<LedgerEntry> history(Connection c, UUID player, String currencyIdOrNull, int limit) throws SQLException {
        String currencyFilter = currencyIdOrNull == null ? "" : " AND currency_id = ?";
        // UNION of two index-backed lookups instead of an OR across two columns.
        String sql = "SELECT * FROM ("
                + "SELECT * FROM " + transactions + " WHERE account_uuid = ?" + currencyFilter
                + " UNION ALL "
                + "SELECT * FROM " + transactions + " WHERE counterparty_uuid = ?" + currencyFilter
                + ") t ORDER BY seq DESC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, player.toString());
            if (currencyIdOrNull != null) {
                ps.setString(i++, currencyIdOrNull);
            }
            ps.setString(i++, player.toString());
            if (currencyIdOrNull != null) {
                ps.setString(i++, currencyIdOrNull);
            }
            ps.setInt(i, limit);
            List<LedgerEntry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readLedger(rs));
                }
            }
            return out;
        }
    }

    private static LedgerEntry readLedger(ResultSet rs) throws SQLException {
        String cp = rs.getString("counterparty_uuid");
        return new LedgerEntry(
                rs.getLong("seq"),
                rs.getString("tx_id"),
                rs.getString("idempotency_key"),
                rs.getString("currency_id"),
                TransactionType.valueOf(rs.getString("tx_type")),
                new Actor(Actor.Type.valueOf(rs.getString("actor_type")), rs.getString("actor_id"), rs.getString("actor_name")),
                UUID.fromString(rs.getString("account_uuid")),
                cp == null ? null : UUID.fromString(cp),
                rs.getLong("amount"),
                nullableLong(rs, "balance_before"),
                rs.getLong("balance_after"),
                nullableLong(rs, "cp_balance_before"),
                nullableLong(rs, "cp_balance_after"),
                rs.getString("reason"),
                rs.getString("server_id"),
                rs.getLong("created_at"));
    }

    public List<TopRow> top(Connection c, String currencyId, int limit, int offset) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT b.player_uuid, p.name, b.balance FROM " + balances + " b"
                + " LEFT JOIN " + players + " p ON p.player_uuid = b.player_uuid"
                + " WHERE b.currency_id = ? ORDER BY b.balance DESC, b.player_uuid ASC LIMIT ? OFFSET ?")) {
            ps.setString(1, currencyId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            List<TopRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
            }
            return out;
        }
    }

    /** Sum of all balances of a currency. Used by tests and audits to verify conservation. */
    public long totalSupply(Connection c, String currencyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM " + balances + " WHERE currency_id = ?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public long countAccounts(Connection c, String currencyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + balances + " WHERE currency_id = ?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public int countLedger(Connection c, String currencyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + transactions + " WHERE currency_id = ?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ players

    public void upsertPlayer(Connection c, UUID player, String name, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.dialect().upsertPlayer(players))) {
            ps.setString(1, player.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /** Most recently seen player with that name (names can move between accounts). */
    public UUID findPlayerByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM " + players
                + " WHERE name_lower = ? ORDER BY updated_at DESC LIMIT 1")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    // ------------------------------------------------------------------ currencies

    public Map<String, Integer> loadScales(Connection c) throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT currency_id, scale FROM " + currencies);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    public void insertCurrency(Connection c, String currencyId, int scale, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + currencies + " (currency_id, scale, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, currencyId);
            ps.setInt(2, scale);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
