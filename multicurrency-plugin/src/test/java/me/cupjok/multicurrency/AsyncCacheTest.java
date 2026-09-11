package me.cupjok.multicurrency;

import me.cupjok.multicurrency.core.cache.AsyncCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCacheTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final List<CompletableFuture<Integer>> pending = new ArrayList<>();
    private final AtomicInteger loads = new AtomicInteger();

    private AsyncCache<String, Integer> cache(long ttl, int max) {
        return new AsyncCache<>(k -> {
            loads.incrementAndGet();
            CompletableFuture<Integer> f = new CompletableFuture<>();
            synchronized (pending) {
                pending.add(f);
            }
            return f;
        }, ttl, max, now::get);
    }

    private CompletableFuture<Integer> last() {
        synchronized (pending) {
            return pending.getLast();
        }
    }

    @Test
    void firstReadDoesNotBlockAndLoadsOnce() {
        AsyncCache<String, Integer> c = cache(5_000, 100);
        assertNull(c.get("a"));
        assertNull(c.get("a"));
        assertEquals(1, loads.get(), "only one load while the first is running");
        last().complete(7);
        assertEquals(7, c.get("a"));
        assertEquals(1, loads.get(), "fresh value is served without a load");
    }

    @Test
    void staleValueIsServedWhileOneRefreshRuns() {
        AsyncCache<String, Integer> c = cache(5_000, 100);
        c.get("a");
        last().complete(1);
        now.addAndGet(5_000);
        assertEquals(1, c.get("a"), "stale value returned immediately");
        assertEquals(1, c.get("a"));
        assertEquals(2, loads.get());
        last().complete(2);
        assertEquals(2, c.get("a"));
        assertEquals(2, loads.get());
    }

    @Test
    void invalidationDiscardsARefreshThatStartedBeforeIt() {
        AsyncCache<String, Integer> c = cache(5_000, 100);
        c.get("a");
        last().complete(10);
        now.addAndGet(6_000);
        c.get("a");
        CompletableFuture<Integer> inFlight = last();
        c.invalidate("a");
        inFlight.complete(10);
        assertEquals(10, c.get("a"), "old value still shown");
        assertEquals(3, loads.get(), "a new load starts after the discarded one");
        last().complete(25);
        assertEquals(25, c.get("a"));
    }

    @Test
    void invalidateMakesAFreshValueStale() {
        AsyncCache<String, Integer> c = cache(60_000, 100);
        c.get("a");
        last().complete(1);
        c.invalidate("a");
        assertEquals(1, c.get("a"));
        assertEquals(2, loads.get());
    }

    @Test
    void failedLoadKeepsTheOldValueAndWaitsForTheTtl() {
        AsyncCache<String, Integer> c = cache(5_000, 100);
        c.get("a");
        last().complete(3);
        now.addAndGet(5_000);
        c.get("a");
        last().completeExceptionally(new RuntimeException("db down"));
        assertEquals(3, c.get("a"));
        assertEquals(2, loads.get(), "no retry storm");
        now.addAndGet(5_000);
        c.get("a");
        assertEquals(3, loads.get());
    }

    @Test
    void loaderThatThrowsIsHandled() {
        AsyncCache<String, Integer> c = new AsyncCache<>(k -> {
            throw new IllegalStateException("executor full");
        }, 1_000, 10, now::get);
        assertNull(c.get("a"));
        assertNull(c.get("a"));
    }

    @Test
    void sizeIsBoundedAndIdleEntriesAreSwept() {
        AsyncCache<String, Integer> c = cache(5_000, 10);
        for (int i = 0; i < 50; i++) {
            now.incrementAndGet();
            c.get("k" + i);
        }
        assertTrue(c.size() <= 10, "size " + c.size());
        now.addAndGet(100_000);
        c.get("fresh");
        c.sweep(50_000);
        assertEquals(1, c.size());
        c.removeIf(k -> k.equals("fresh"));
        assertEquals(0, c.size());
    }

    @Test
    void concurrentReadersTriggerOneLoadPerKey() throws Exception {
        AtomicInteger n = new AtomicInteger();
        CompletableFuture<Integer> gate = new CompletableFuture<>();
        AsyncCache<String, Integer> c = new AsyncCache<>(k -> {
            n.incrementAndGet();
            return gate;
        }, 60_000, 100, now::get);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 400; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                c.get("same");
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, n.get());
        gate.complete(5);
        assertEquals(5, c.get("same"));
    }
}
