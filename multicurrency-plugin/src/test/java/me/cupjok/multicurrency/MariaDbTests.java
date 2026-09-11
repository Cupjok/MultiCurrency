package me.cupjok.multicurrency;

import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;

/**
 * Runs the shared suites against MariaDB/MySQL. Skipped unless MC_TEST_MARIADB_HOST is set, e.g.
 * {@code MC_TEST_MARIADB_HOST=127.0.0.1 MC_TEST_MARIADB_USER=mctest MC_TEST_MARIADB_PASSWORD=... mvn test}.
 *
 * <p>The concurrency suite uses two independent services with separate connection pools against the
 * same tables. That is the multi-server case: no JVM-local lock is shared, so correctness must come
 * from the database alone.
 */
class MariaDbTests {

    private final String prefix = TestSupport.randomPrefix();

    @AfterEach
    void cleanup() throws Exception {
        TestSupport.dropTables(prefix);
    }

    @Nested
    class Contract extends ServiceContractTest {
        @Override
        protected Database createDatabase() {
            return TestSupport.mariadb(prefix, 8);
        }

        @Override
        protected int threads() {
            return 8;
        }
    }

    @Nested
    class TwoServerConcurrency extends ConcurrencyTest {
        @Override
        protected Database createDatabase() {
            return TestSupport.mariadb(prefix, 8);
        }

        @Override
        protected int threads() {
            return 8;
        }

        @Override
        protected CurrencyService createSecondService() {
            CurrencyService s = new CurrencyService(TestSupport.mariadb(prefix, 8), "server-b", 8, TestSupport.LOG);
            s.start(TestSupport.registry()).join();
            return s;
        }
    }
}
