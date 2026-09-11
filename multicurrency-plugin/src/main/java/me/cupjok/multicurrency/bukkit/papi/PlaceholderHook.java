package me.cupjok.multicurrency.bukkit.papi;

import me.cupjok.multicurrency.bukkit.MultiCurrencyPlugin;

/**
 * Registers the PlaceholderAPI expansion. Only this class references PlaceholderAPI types, and it is
 * loaded only when PlaceholderAPI is installed.
 */
public final class PlaceholderHook {

    /** Leaderboard positions available through placeholders. */
    public static final int TOP_SIZE = 10;

    private final MultiCurrencyExpansion expansion;

    private PlaceholderHook(MultiCurrencyExpansion expansion) {
        this.expansion = expansion;
    }

    public static PlaceholderHook register(MultiCurrencyPlugin plugin) {
        MultiCurrencyExpansion expansion = new MultiCurrencyExpansion(plugin);
        if (expansion.register()) {
            plugin.getLogger().info("Registered PlaceholderAPI placeholders (%multicurrency_...%).");
        } else {
            plugin.getLogger().warning("PlaceholderAPI refused the MultiCurrency expansion.");
        }
        return new PlaceholderHook(expansion);
    }

    public void unregister() {
        expansion.unregister();
    }
}
