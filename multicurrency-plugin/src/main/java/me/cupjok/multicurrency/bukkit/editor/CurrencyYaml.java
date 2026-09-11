package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.bukkit.config.CurrencyConfigLoader;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure edits of the {@code currencies:} section of a loaded {@code config.yml}, plus validation of
 * admin input. No I/O and no server access, so every rule here is unit-tested.
 *
 * <p>Edits change the YAML only. They take effect through the normal reload path, which validates the
 * whole configuration again and applies the service's scale-lock rules.
 */
public final class CurrencyYaml {

    public static final String SECTION = "currencies";
    public static final int MAX_NAME = 32;
    public static final int MAX_SYMBOL = 16;
    public static final int MAX_FORMAT = 64;

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,20}(\\.\\d{1,18})?");
    /** Legacy colour codes ({@code &a}, {@code &x}) that other plugins may interpret in placeholder output. */
    private static final Pattern LEGACY_CODE = Pattern.compile("&[0-9a-fk-orx]", Pattern.CASE_INSENSITIVE);

    /** Editable currency settings. {@code economy} settings change what players can hold or do. */
    public enum Field {
        DISPLAY_NAME("display-name", false),
        SYMBOL("symbol", false),
        FORMAT("format", false),
        ENABLED("enabled", true),
        TRANSFER("transfer-enabled", true),
        DECIMALS("decimals", true),
        STARTING("starting-balance", true),
        MAX("max-balance", true);

        private final String key;
        private final boolean economy;

        Field(String key, boolean economy) {
            this.key = key;
            this.economy = economy;
        }

        public String key() {
            return key;
        }

        public boolean economy() {
            return economy;
        }
    }

    /** Rejected admin input. {@code reason} is a message key suffix under {@code editor.invalid}. */
    public static final class InvalidInput extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public InvalidInput(String reason, String detail) {
            super(detail);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private CurrencyYaml() {
    }

    // ------------------------------------------------------------------ queries

    public static Set<String> ids(YamlConfiguration yaml) {
        ConfigurationSection s = yaml.getConfigurationSection(SECTION);
        return s == null ? Set.of() : new LinkedHashSet<>(s.getKeys(false));
    }

    public static boolean exists(YamlConfiguration yaml, String id) {
        return yaml.isConfigurationSection(SECTION + "." + id);
    }

    // ------------------------------------------------------------------ edits

    /**
     * Adds a new currency. It starts <b>disabled</b>: its decimal places are not locked until it is
     * enabled for the first time, so a wrong choice can still be corrected.
     */
    public static void create(YamlConfiguration yaml, String id, int decimals) throws InvalidInput {
        String clean = parseId(id);
        if (exists(yaml, clean)) {
            throw new InvalidInput("exists", clean);
        }
        if (decimals < 0 || decimals > CurrencyDefinition.MAX_SCALE) {
            throw new InvalidInput("decimals", String.valueOf(decimals));
        }
        if (!yaml.isConfigurationSection(SECTION)) {
            yaml.createSection(SECTION);
        }
        String base = SECTION + "." + clean + ".";
        yaml.set(base + Field.DISPLAY_NAME.key(), clean);
        yaml.set(base + Field.SYMBOL.key(), "");
        yaml.set(base + Field.ENABLED.key(), false);
        yaml.set(base + Field.DECIMALS.key(), decimals);
        yaml.set(base + Field.STARTING.key(), "0");
        yaml.set(base + Field.TRANSFER.key(), false);
        yaml.set(base + Field.FORMAT.key(), "{symbol} {amount}");
    }

    /** Sets one field. {@code value} is a String, Boolean or Integer; {@code null} removes the key (only for max-balance). */
    public static void set(YamlConfiguration yaml, String id, Field field, Object value) throws InvalidInput {
        if (!exists(yaml, id)) {
            throw new InvalidInput("missing", id);
        }
        if (value == null && field != Field.MAX) {
            throw new InvalidInput("value", field.key());
        }
        yaml.set(SECTION + "." + id + "." + field.key(), value);
    }

    public static void delete(YamlConfiguration yaml, String id) throws InvalidInput {
        if (!exists(yaml, id)) {
            throw new InvalidInput("missing", id);
        }
        yaml.set(SECTION + "." + id, null);
    }

    /** Validates every currency in the edited file with the loader that {@code /currency reload} uses. */
    public static CurrencyConfigLoader.Result validate(YamlConfiguration yaml) {
        return CurrencyConfigLoader.load(yaml.getConfigurationSection(SECTION));
    }

    // ------------------------------------------------------------------ input parsing

    public static String parseId(String raw) throws InvalidInput {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!CurrencyDefinition.ID_PATTERN.matcher(id).matches()) {
            throw new InvalidInput("id", raw);
        }
        return id;
    }

    /**
     * Turns chat input into the value to store for {@code field}.
     *
     * @return a String, or {@code null} for "no maximum"
     */
    public static String parseText(Field field, String raw) throws InvalidInput {
        String v = raw == null ? "" : raw.strip();
        return switch (field) {
            case DISPLAY_NAME -> {
                requireSafe(v, 1, MAX_NAME, "name");
                yield v;
            }
            case SYMBOL -> {
                if (v.equalsIgnoreCase("none") || v.equals("-")) {
                    yield "";
                }
                requireSafe(v, 1, MAX_SYMBOL, "symbol");
                yield v;
            }
            case FORMAT -> {
                requireSafe(v, 1, MAX_FORMAT, "format");
                if (!v.contains("{amount}")) {
                    throw new InvalidInput("format", v);
                }
                yield v;
            }
            case STARTING -> {
                if (!AMOUNT.matcher(v).matches()) {
                    throw new InvalidInput("amount", v);
                }
                yield v;
            }
            case MAX -> {
                if (v.equalsIgnoreCase("none")) {
                    yield null;
                }
                if (!AMOUNT.matcher(v).matches()) {
                    throw new InvalidInput("amount", v);
                }
                yield v;
            }
            default -> throw new InvalidInput("value", field.key());
        };
    }

    /**
     * Refuses control characters, section signs, MiniMessage-like tags and legacy colour codes. Display
     * values are inserted unparsed by MultiCurrency itself, but placeholder output reaches other plugins
     * that may parse formatting.
     */
    private static void requireSafe(String v, int min, int max, String reason) throws InvalidInput {
        int length = v.codePointCount(0, v.length());
        if (length < min || length > max) {
            throw new InvalidInput(reason, v);
        }
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            // FORMAT characters (bidi overrides, zero-width space) are refused, except the zero-width
            // joiner that combined emoji need.
            boolean invisible = Character.getType(ch) == Character.FORMAT && ch != '\u200D';
            if (ch < 0x20 || ch == 0x7f || ch == '§' || ch == '<' || ch == '>' || invisible) {
                throw new InvalidInput("characters", v);
            }
        }
        if (LEGACY_CODE.matcher(v).find()) {
            throw new InvalidInput("characters", v);
        }
    }

    /** First validation error that mentions {@code id}, or the first error overall. */
    public static String firstError(List<String> errors, String id) {
        for (String e : errors) {
            if (e.contains("'" + id + "'")) {
                return e;
            }
        }
        return errors.isEmpty() ? null : errors.getFirst();
    }
}
