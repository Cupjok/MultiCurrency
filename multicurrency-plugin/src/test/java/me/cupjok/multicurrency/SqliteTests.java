package me.cupjok.multicurrency;

import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Runs the shared suites against SQLite. */
class SqliteTests {

    @TempDir
    Path dir;

    @Nested
    class Contract extends ServiceContractTest {
        @Override
        protected Database createDatabase() {
            return TestSupport.sqlite(dir);
        }

        @Override
        protected int threads() {
            return 1;
        }
    }

    @Nested
    class Concurrency extends ConcurrencyTest {
        @Override
        protected Database createDatabase() {
            return TestSupport.sqlite(dir);
        }

        @Override
        protected int threads() {
            return 1;
        }

        /** A second instance with its own connection on the same file: exercises SQLite's file locking. */
        @Override
        protected CurrencyService createSecondService() {
            CurrencyService s = new CurrencyService(TestSupport.sqlite(dir), "server-b", 1, TestSupport.LOG);
            s.start(TestSupport.registry()).join();
            return s;
        }
    }
}
