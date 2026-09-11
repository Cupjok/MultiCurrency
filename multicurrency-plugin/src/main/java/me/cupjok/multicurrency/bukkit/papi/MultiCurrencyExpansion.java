package me.cupjok.multicurrency.bukkit.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.cupjok.multicurrency.api.BalanceEntry;
import me.cupjok.multicurrency.bukkit.MultiCurrencyPlugin;
import me.cupjok.multicurrency.core.cache.AsyncCache;
import me.cupjok.multicurrency.core.cache.BalanceKey;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.service.CurrencyService;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code %multicurrency_...%} placeholders.
 *
 * <p>Placeholders are requested on the server thread, so they are served from a short-lived,
 * non-blocking cache ({@link AsyncCache}); a missing value shows the configured loading text and is
 * fetched in the background. Changes made on this server refresh the cached value immediately; changes
 * made by another server that shares the database appear within the cache time.
 *
 * <pre>
 * balance_&lt;id&gt;            1,234.50
 * balance_formatted_&lt;id&gt;  🪙 1,234.50 (the currency's format)
 * balance_raw_&lt;id&gt;        1234.50 (plain number, for sorting and maths)
 * name_&lt;id&gt;, symbol_&lt;id&gt;
 * top_name_&lt;id&gt;_&lt;rank&gt;, top_balance_&lt;id&gt;_&lt;rank&gt;, top_balance_raw_&lt;id&gt;_&lt;rank&gt;  (rank 1-10)
 * </pre>
 */
final class MultiCurrencyExpansion extends PlaceholderExpansion {

    private final MultiCurrencyPlugin plugin;

    MultiCurrencyExpansion(MultiCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "multicurrency";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cupjok";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        CurrencyService service = plugin.service();
        if (service == null) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_name_")) {
            return top(service, p.substring("top_name_".length()), Kind.NAME);
        }
        if (p.startsWith("top_balance_raw_")) {
            return top(service, p.substring("top_balance_raw_".length()), Kind.RAW);
        }
        if (p.startsWith("top_balance_")) {
            return top(service, p.substring("top_balance_".length()), Kind.NUMBER);
        }
        if (p.startsWith("name_")) {
            return find(service, p.substring("name_".length())).map(CurrencyDefinition::displayName).orElse(null);
        }
        if (p.startsWith("symbol_")) {
            return find(service, p.substring("symbol_".length())).map(CurrencyDefinition::symbol).orElse(null);
        }
        if (p.startsWith("balance_raw_") && find(service, p.substring("balance_raw_".length())).isPresent()) {
            return balance(service, player, p.substring("balance_raw_".length()), Kind.RAW);
        }
        if (p.startsWith("balance_formatted_") && find(service, p.substring("balance_formatted_".length())).isPresent()) {
            return balance(service, player, p.substring("balance_formatted_".length()), Kind.FORMATTED);
        }
        if (p.startsWith("balance_")) {
            // Also reached by ids such as "raw_x" that share a prefix with the forms above.
            return balance(service, player, p.substring("balance_".length()), Kind.NUMBER);
        }
        return null;
    }

    private enum Kind { NUMBER, FORMATTED, RAW, NAME }

    private static Optional<CurrencyDefinition> find(CurrencyService service, String id) {
        return service.registry().find(id);
    }

    private String loading() {
        return plugin.getConfig().getString("placeholders.loading", "...");
    }

    private String balance(CurrencyService service, OfflinePlayer player, String id, Kind kind) {
        Optional<CurrencyDefinition> found = find(service, id);
        if (found.isEmpty()) {
            return null;
        }
        if (player == null) {
            return "";
        }
        AsyncCache<BalanceKey, BigDecimal> cache = plugin.balanceCache();
        CurrencyDefinition c = found.get();
        BigDecimal value = cache == null ? null : cache.get(new BalanceKey(player.getUniqueId(), c.id()));
        if (value == null) {
            return loading();
        }
        return render(c, value, kind);
    }

    private static String render(CurrencyDefinition c, BigDecimal value, Kind kind) {
        return switch (kind) {
            case FORMATTED -> c.format(value);
            case RAW -> value.toPlainString();
            default -> c.formatNumber(value);
        };
    }

    private String top(CurrencyService service, String rest, Kind kind) {
        int sep = rest.lastIndexOf('_');
        if (sep <= 0) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(rest.substring(sep + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        Optional<CurrencyDefinition> found = find(service, rest.substring(0, sep));
        if (found.isEmpty() || rank < 1 || rank > PlaceholderHook.TOP_SIZE) {
            return null;
        }
        AsyncCache<String, List<BalanceEntry>> cache = plugin.topCache();
        List<BalanceEntry> rows = cache == null ? null : cache.get(found.get().id());
        if (rows == null) {
            return loading();
        }
        if (rank > rows.size()) {
            return plugin.getConfig().getString("placeholders.top-empty", "-");
        }
        BalanceEntry row = rows.get(rank - 1);
        if (kind == Kind.NAME) {
            return row.lastKnownName() != null ? row.lastKnownName() : row.player().toString().substring(0, 8);
        }
        return render(found.get(), row.balance(), kind);
    }
}
