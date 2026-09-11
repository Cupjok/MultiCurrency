package me.cupjok.multicurrency.core.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Display-only, non-blocking read cache (stale-while-revalidate).
 *
 * <p>{@link #get} never waits: it returns the cached value (possibly stale, or {@code null} before the
 * first load) and starts at most one background refresh per key when the value is older than the TTL.
 * {@link #invalidate} bumps a per-key generation, so a refresh that started before the invalidation
 * cannot store its (older) result.
 *
 * <p><b>Never use a cached value to decide a balance change.</b> Mutations always read and lock the
 * database row inside their own transaction. This cache only feeds placeholders.
 */
public final class AsyncCache<K, V> {

    private static final class Entry<V> {
        V value;
        long loadedAt = -1;
        long generation;
        boolean loading;
        long lastAccess;
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Function<K, CompletableFuture<V>> loader;
    private final long ttlMillis;
    private final int maxEntries;
    private final LongSupplier clock;

    public AsyncCache(Function<K, CompletableFuture<V>> loader, long ttlMillis, int maxEntries, LongSupplier clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ttlMillis = Math.max(0, ttlMillis);
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Current value or {@code null} if none was loaded yet. Starts a refresh if the value is missing or stale. */
    public V get(K key) {
        long now = clock.getAsLong();
        Entry<V> e = entries.computeIfAbsent(key, k -> new Entry<>());
        boolean start;
        long generation;
        V value;
        synchronized (e) {
            e.lastAccess = now;
            start = !e.loading && (e.loadedAt < 0 || now - e.loadedAt >= ttlMillis);
            if (start) {
                e.loading = true;
            }
            generation = e.generation;
            value = e.value;
        }
        if (start) {
            refresh(key, e, generation);
        }
        if (entries.size() > maxEntries) {
            evict();
        }
        return value;
    }

    private void refresh(K key, Entry<V> e, long generation) {
        CompletableFuture<V> future;
        try {
            future = loader.apply(key);
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        future.whenComplete((v, err) -> {
            synchronized (e) {
                e.loading = false;
                if (e.generation != generation) {
                    // Invalidated while loading: the result may predate the change. Stay stale.
                    return;
                }
                if (err == null) {
                    e.value = v;
                }
                // On failure keep the previous value and retry after the TTL instead of hammering the database.
                e.loadedAt = clock.getAsLong();
            }
        });
    }

    /** Marks the value stale and discards any refresh that is already running. The old value stays visible until reloaded. */
    public void invalidate(K key) {
        Entry<V> e = entries.get(key);
        if (e != null) {
            synchronized (e) {
                e.generation++;
                e.loadedAt = -1;
            }
        }
    }

    /** Removes every entry whose key matches. */
    public void removeIf(Predicate<K> filter) {
        entries.keySet().removeIf(filter);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** Removes entries that were not read for {@code idleMillis}. */
    public void sweep(long idleMillis) {
        long cutoff = clock.getAsLong() - idleMillis;
        entries.values().removeIf(e -> {
            synchronized (e) {
                return e.lastAccess < cutoff;
            }
        });
    }

    /** Drops the least recently read entries until the cache is at 90% of its capacity. */
    private synchronized void evict() {
        int target = (int) (maxEntries * 0.9);
        int excess = entries.size() - target;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<K, Long>> byAccess = new ArrayList<>();
        entries.forEach((k, e) -> {
            synchronized (e) {
                byAccess.add(Map.entry(k, e.lastAccess));
            }
        });
        byAccess.sort(Map.Entry.comparingByValue(Comparator.naturalOrder()));
        for (int i = 0; i < excess && i < byAccess.size(); i++) {
            entries.remove(byAccess.get(i).getKey());
        }
    }
}
