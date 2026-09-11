package me.cupjok.multicurrency;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.TransactionContext;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import me.cupjok.multicurrency.core.storage.Database;
import org.junit.jupiter.api.Assumptions;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TestSupport {

    public static final Logger LOG = Logger.getLogger("MultiCurrencyTest");

    static {
        LOG.setLevel(Level.SEVERE);
    }

    private TestSupport() {
    }

    public static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    public static TransactionContext ctx() {
        return TransactionContext.of(Actor.system("test"), "test");
    }

    public static TransactionContext ctx(String key) {
        return new TransactionContext(Actor.system("test"), "test", key);
    }

    /**
     * Test currencies. None of these ids are known to the implementation; they only exist here.
     */
    public static CurrencyRegistry registry() {
        return new CurrencyRegistry(List.of(
                new CurrencyDefinition("coins", "Coins", "C", true, 2, d("0"), d("1000000"), true, null),
                new CurrencyDefinition("gems", "Gems", "G", true, 0, d("0"), null, false, null),
                new CurrencyDefinition("capped", "Capped", "", true, 0, d("0"), d("100"), true, null),
                new CurrencyDefinition("starter", "Starter", "", true, 0, d("50"), null, true, null),
                new CurrencyDefinition("off", "Off", "", false, 0, d("0"), null, true, null)));
    }

    public static Database sqlite(Path dir) {
        return Database.sqlite(dir.resolve("test.db"), "mc_", null, LOG);
    }

    public static boolean mariaDbConfigured() {
        return System.getenv("MC_TEST_MARIADB_HOST") != null;
    }

    /**
     * MariaDB/MySQL from MC_TEST_MARIADB_{HOST,PORT,DB,USER,PASSWORD}; skips the test when unset.
     * MC_TEST_MARIADB_PROPS adds driver properties, e.g. {@code sslMode=trust} for MySQL 8 over TLS.
     */
    public static Database mariadb(String prefix, int poolSize) {
        Assumptions.assumeTrue(mariaDbConfigured(), "MC_TEST_MARIADB_HOST not set; MariaDB tests skipped");
        return Database.mariadb(System.getenv("MC_TEST_MARIADB_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("MC_TEST_MARIADB_PORT", "3306")),
                System.getenv().getOrDefault("MC_TEST_MARIADB_DB", "multicurrency_test"),
                System.getenv().getOrDefault("MC_TEST_MARIADB_USER", "mctest"),
                System.getenv().getOrDefault("MC_TEST_MARIADB_PASSWORD", ""),
                poolSize, driverProperties(), prefix, null, LOG);
    }

    private static Map<String, String> driverProperties() {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        String raw = System.getenv("MC_TEST_MARIADB_PROPS");
        if (raw != null) {
            for (String pair : raw.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        return props;
    }

    public static String randomPrefix() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "_";
    }

    /** Drops every table created with {@code prefix} in a MariaDB test database. */
    public static void dropTables(String prefix) throws Exception {
        if (!mariaDbConfigured()) {
            return;
        }
        try (Database db = mariadb(prefix, 1)) {
            db.transaction(c -> {
                List<String> tables = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW TABLES LIKE '" + prefix + "%'")) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
                for (String t : tables) {
                    try (Statement st = c.createStatement()) {
                        st.execute("DROP TABLE IF EXISTS `" + t + "`");
                    }
                }
                return null;
            });
        }
    }

    public static long scalar(Database db, String sql) throws Exception {
        return db.transaction((Connection c) -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(db.sql(sql))) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }
}
