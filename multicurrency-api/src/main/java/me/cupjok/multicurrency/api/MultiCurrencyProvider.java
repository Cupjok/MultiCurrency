package me.cupjok.multicurrency.api;

import java.util.Optional;

/**
 * Static access to the running {@link MultiCurrencyApi}. Bukkit's {@code ServicesManager} works too.
 */
public final class MultiCurrencyProvider {

    private static volatile MultiCurrencyApi instance;

    private MultiCurrencyProvider() {
    }

    /**
     * @throws IllegalStateException when MultiCurrency is not loaded or has been disabled
     */
    public static MultiCurrencyApi get() {
        MultiCurrencyApi api = instance;
        if (api == null) {
            throw new IllegalStateException("MultiCurrency is not loaded");
        }
        return api;
    }

    public static Optional<MultiCurrencyApi> find() {
        return Optional.ofNullable(instance);
    }

    /** Internal: called by the MultiCurrency plugin only. */
    public static void register(MultiCurrencyApi api) {
        instance = api;
    }

    /** Internal: called by the MultiCurrency plugin only. */
    public static void unregister(MultiCurrencyApi api) {
        if (instance == api) {
            instance = null;
        }
    }
}
