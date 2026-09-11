package me.cupjok.multicurrency;

import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.api.TransactionResult;
import me.cupjok.multicurrency.api.TransactionType;
import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import static me.cupjok.multicurrency.TestSupport.ctx;
import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Race-condition regression tests. The central property: however requests interleave, money is
 * never created, lost or overspent. {@link #createSecondService()} lets a backend run the same attack
 * from two independent service instances (two pools, like two servers).
 */
abstract class ConcurrencyTest {

    protected CurrencyService service;
    protected CurrencyService other;
    protected Database db;

    protected abstract Database createDatabase() throws Exception;

    /** Second, independent service on the same storage, or null when the backend has none. */
    protected abstract CurrencyService createSecondService() throws Exception;

    protected abstract int threads();

    @BeforeEach
    void setUp() throws Exception {
        db = createDatabase();
        service = new CurrencyService(db, "server-a", threads(), TestSupport.LOG);
        service.start(TestSupport.registry()).join();
        other = createSecondService();
        if (other == null) {
            other = service;
        }
    }

    @AfterEach
    void tearDown() {
        // Both are null when setUp was skipped (no MariaDB/MySQL configured).
        if (other != null && other != service) {
            other.close();
        }
        if (service != null) {
            service.close();
        }
    }

    /** Fires {@code n} calls at once from real threads (a start gate maximises overlap). */
    private List<TransactionResult> burst(int n, IntFunction<CompletableFuture<TransactionResult>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 64));
        CountDownLatch gate = new CountDownLatch(1);
        List<CompletableFuture<TransactionResult>> results = new ArrayList<>();
        List<CompletableFuture<Void>> submitted = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            CompletableFuture<TransactionResult> slot = new CompletableFuture<>();
            results.add(slot);
            submitted.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                    call.apply(idx).whenComplete((r, e) -> {
                        if (e != null) {
                            slot.completeExceptionally(e);
                        } else {
                            slot.complete(r);
                        }
                    });
                } catch (InterruptedException e) {
                    slot.completeExceptionally(e);
                }
            }, pool));
        }
        gate.countDown();
        CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new)).join();
        List<TransactionResult> out = new ArrayList<>();
        for (CompletableFuture<TransactionResult> f : results) {
            out.add(f.join());
        }
        pool.shutdown();
        return out;
    }

    private CurrencyService pick(int i) {
        return i % 2 == 0 ? service : other;
    }

    private static long successes(List<TransactionResult> results) {
        return results.stream().filter(TransactionResult::success).count();
    }

    /**
     * Regression for the classic economy-plugin exploit: many "remove 10" requests race; with a
     * check-then-act implementation several checks pass before any debit lands.
     */
    @Test
    void concurrentWithdrawalsNeverOverspend() throws Exception {
        UUID p = UUID.randomUUID();
        assertTrue(service.deposit(p, "gems", d("100"), ctx()).join().success());
        List<TransactionResult> results = burst(200, i -> pick(i).withdraw(p, "gems", d("10"), ctx()));
        assertEquals(10, successes(results));
        results.stream().filter(r -> !r.success()).forEach(r -> assertEquals(FailureReason.INSUFFICIENT_FUNDS, r.failureReason()));
        assertEquals(d("0"), service.balance(p, "gems").join());
        long withdrawals = service.history(p, "gems", 100).join().stream().filter(r -> r.type() == TransactionType.WITHDRAW).count();
        assertEquals(10, withdrawals, "ledger matches applied debits");
    }

    /** The has()-then-withdraw() pattern used by careless callers still cannot overspend. */
    @Test
    void checkThenActCallersCannotDoubleSpend() throws Exception {
        UUID p = UUID.randomUUID();
        service.deposit(p, "gems", d("50"), ctx()).join();
        List<TransactionResult> results = burst(100, i -> pick(i).has(p, "gems", d("5"))
                .thenCompose(ok -> ok
                        ? pick(i).withdraw(p, "gems", d("5"), ctx())
                        : CompletableFuture.completedFuture(TransactionResult.failed(FailureReason.INSUFFICIENT_FUNDS, TransactionType.WITHDRAW, "gems", d("5")))));
        assertEquals(10, successes(results));
        assertEquals(d("0"), service.balance(p, "gems").join());
    }

    /** One balance, many simultaneous transfers of all of it to different recipients. */
    @Test
    void concurrentTransfersCannotDoubleSpend() throws Exception {
        UUID sender = UUID.randomUUID();
        service.deposit(sender, "coins", d("100"), ctx()).join();
        List<UUID> recipients = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recipients.add(UUID.randomUUID());
        }
        List<TransactionResult> results = burst(50, i -> pick(i).transfer(sender, recipients.get(i), "coins", d("100"), ctx()));
        assertEquals(1, successes(results));
        assertEquals(d("0.00"), service.balance(sender, "coins").join());
        BigDecimal received = BigDecimal.ZERO;
        for (UUID r : recipients) {
            received = received.add(service.balance(r, "coins").join());
        }
        assertEquals(d("100.00"), received);
        assertEquals(d("100.00"), service.totalSupply("coins").join());
    }

    /** Random transfers in both directions between a few accounts: supply is conserved, nobody goes negative, no deadlock. */
    @Test
    void randomConcurrentTransfersConserveSupply() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID p = UUID.randomUUID();
            accounts.add(p);
            service.deposit(p, "coins", d("100"), ctx()).join();
        }
        Random random = new Random(42);
        int n = 400;
        UUID[] from = new UUID[n];
        UUID[] to = new UUID[n];
        BigDecimal[] amount = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            from[i] = accounts.get(random.nextInt(accounts.size()));
            UUID t;
            do {
                t = accounts.get(random.nextInt(accounts.size()));
            } while (t.equals(from[i]));
            to[i] = t;
            amount[i] = BigDecimal.valueOf(1 + random.nextInt(4000), 2);
        }
        List<TransactionResult> results = burst(n, i -> pick(i).transfer(from[i], to[i], "coins", amount[i], ctx()));
        for (TransactionResult r : results) {
            assertTrue(r.success() || r.failureReason() == FailureReason.INSUFFICIENT_FUNDS, "unexpected " + r.failureReason());
        }
        BigDecimal total = BigDecimal.ZERO;
        for (UUID p : accounts) {
            BigDecimal b = service.balance(p, "coins").join();
            assertTrue(b.signum() >= 0);
            total = total.add(b);
        }
        assertEquals(d("600.00"), total);
        assertEquals(d("600.00"), service.totalSupply("coins").join());
    }

    @Test
    void concurrentDuplicateRequestsApplyOnce() throws Exception {
        UUID p = UUID.randomUUID();
        List<TransactionResult> results = burst(40, i -> pick(i).deposit(p, "coins", d("7.50"), ctx("reward:42")));
        assertEquals(1, successes(results));
        String id = results.stream().filter(TransactionResult::success).findFirst().orElseThrow().transactionId();
        for (TransactionResult r : results) {
            if (!r.success()) {
                assertEquals(FailureReason.DUPLICATE_TRANSACTION, r.failureReason());
                assertEquals(id, r.transactionId());
            }
        }
        assertEquals(d("7.50"), service.balance(p, "coins").join());
    }

    @Test
    void concurrentDepositsAreExact() throws Exception {
        UUID p = UUID.randomUUID();
        List<TransactionResult> results = burst(300, i -> pick(i).deposit(p, "coins", d("0.01"), ctx()));
        assertEquals(300, successes(results));
        assertEquals(d("3.00"), service.balance(p, "coins").join());
    }

    /** Many first-time writes on a fresh account create it exactly once (one INITIAL entry, one starting balance). */
    @Test
    void concurrentAccountCreationGrantsStartingBalanceOnce() throws Exception {
        UUID p = UUID.randomUUID();
        List<TransactionResult> results = burst(30, i -> pick(i).deposit(p, "starter", d("1"), ctx()));
        assertEquals(30, successes(results));
        assertEquals(d("80"), service.balance(p, "starter").join());
        long initial = service.history(p, "starter", 100).join().stream().filter(r -> r.type() == TransactionType.INITIAL).count();
        assertEquals(1, initial);
    }

    /** Balance limit under concurrent credits: never exceeded. */
    @Test
    void concurrentCreditsRespectBalanceLimit() throws Exception {
        UUID p = UUID.randomUUID();
        List<TransactionResult> results = burst(60, i -> pick(i).deposit(p, "capped", d("7"), ctx()));
        assertEquals(14, successes(results));
        assertEquals(d("98"), service.balance(p, "capped").join());
    }
}
