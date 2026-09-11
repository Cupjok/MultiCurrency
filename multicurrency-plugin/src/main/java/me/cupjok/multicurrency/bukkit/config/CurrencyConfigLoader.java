package me.cupjok.multicurrency.bukkit.config;

import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code currencies:} section into validated {@link CurrencyDefinition}s. An invalid
 * currency is skipped and reported; it never loads with a guessed value.
 */
public final class CurrencyConfigLoader {

    public record Result(List<CurrencyDefinition> currencies, List<String> errors) {
    }

    private CurrencyConfigLoader() {
    }

    public static Result load(ConfigurationSection section) {
        List<CurrencyDefinition> currencies = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (section == null) {
            errors.add("no 'currencies' section found; no currencies are available");
            return new Result(currencies, errors);
        }
        for (String id : section.getKeys(false)) {
            if (!CurrencyDefinition.ID_PATTERN.matcher(id).matches()) {
                errors.add("currency id '" + id + "' is invalid: use 1-32 lower-case letters, digits or '_'");
                continue;
            }
            ConfigurationSection c = section.getConfigurationSection(id);
            if (c == null) {
                errors.add("currency '" + id + "' must be a section");
                continue;
            }
            try {
                currencies.add(new CurrencyDefinition(
                        id,
                        c.getString("display-name", id),
                        c.getString("symbol", ""),
                        c.getBoolean("enabled", true),
                        integer(c, "decimals", 0),
                        decimal(c.get("starting-balance"), "starting-balance"),
                        decimal(c.get("max-balance"), "max-balance"),
                        // Fail closed: a currency is not transferable unless explicitly allowed.
                        c.getBoolean("transfer-enabled", false),
                        c.getString("format", CurrencyDefinition.DEFAULT_FORMAT)));
            } catch (IllegalArgumentException e) {
                errors.add("currency '" + id + "' skipped: " + e.getMessage());
            }
        }
        return new Result(currencies, errors);
    }

    private static int integer(ConfigurationSection c, String key, int def) {
        Object o = c.get(key);
        if (o == null) {
            return def;
        }
        if (o instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException(key + " must be a whole number");
    }

    /** Accepts numbers or quoted strings. Quoting is recommended for decimals to avoid YAML floats. */
    static BigDecimal decimal(Object o, String key) {
        if (o == null) {
            return null;
        }
        try {
            if (o instanceof Integer || o instanceof Long) {
                return BigDecimal.valueOf(((Number) o).longValue());
            }
            if (o instanceof Number || o instanceof String) {
                // Double#toString is the shortest exact representation of what YAML parsed.
                return new BigDecimal(o.toString().trim());
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new IllegalArgumentException(key + " '" + o + "' is not a number");
    }
}
