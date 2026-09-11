package me.cupjok.multicurrency.core.currency;

import me.cupjok.multicurrency.api.Currency;
import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.core.Rejection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One validated, immutable currency configuration.
 *
 * <p>Balances are stored as a {@code long} count of minor units: {@code amount * 10^scale}. Every
 * conversion between {@link BigDecimal} and minor units goes through this class and is exact; a value
 * that cannot be represented exactly is rejected, never rounded.
 */
public final class CurrencyDefinition implements Currency {

    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");
    public static final int MAX_SCALE = 8;
    public static final String DEFAULT_FORMAT = "{symbol}{amount}";

    private final String id;
    private final String displayName;
    private final String symbol;
    private final boolean enabled;
    private final int scale;
    private final long startingMinor;
    private final long maxMinor;
    private final boolean transferEnabled;
    private final String format;

    public CurrencyDefinition(String id, String displayName, String symbol, boolean enabled, int scale,
                              BigDecimal startingBalance, BigDecimal maxBalance, boolean transferEnabled, String format) {
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("currency id '" + id + "' must match " + ID_PATTERN.pattern());
        }
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale of '" + id + "' must be between 0 and " + MAX_SCALE);
        }
        this.id = id;
        this.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        this.symbol = symbol == null ? "" : symbol;
        this.enabled = enabled;
        this.scale = scale;
        this.transferEnabled = transferEnabled;
        this.format = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
        this.maxMinor = maxBalance == null ? Long.MAX_VALUE : exactMinor(maxBalance, scale, "max-balance");
        if (this.maxMinor <= 0) {
            throw new IllegalArgumentException("max-balance of '" + id + "' must be positive");
        }
        this.startingMinor = startingBalance == null ? 0 : exactMinor(startingBalance, scale, "starting-balance");
        if (startingMinor < 0 || startingMinor > maxMinor) {
            throw new IllegalArgumentException("starting-balance of '" + id + "' must be between 0 and max-balance");
        }
    }

    private static long exactMinor(BigDecimal value, int scale, String what) {
        try {
            return value.stripTrailingZeros().setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what + " " + value.toPlainString()
                    + " is not representable with " + scale + " decimal places");
        }
    }

    /**
     * Converts a caller-supplied amount to minor units.
     *
     * @param allowZero {@code true} for {@code set}, {@code false} for deposit/withdraw/transfer
     */
    public long toMinor(BigDecimal amount, boolean allowZero) throws Rejection {
        if (amount == null) {
            throw new Rejection(FailureReason.INVALID_AMOUNT);
        }
        int signum = amount.signum();
        if (signum < 0 || (signum == 0 && !allowZero)) {
            throw new Rejection(FailureReason.INVALID_AMOUNT);
        }
        if (signum == 0) {
            return 0;
        }
        // Cheap magnitude guard before any arithmetic: rejects 1E+999999999 without materialising it.
        if ((long) amount.precision() - amount.scale() > 19) {
            throw new Rejection(FailureReason.AMOUNT_TOO_LARGE);
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > scale) {
            throw new Rejection(FailureReason.INVALID_PRECISION);
        }
        long minor;
        try {
            minor = stripped.movePointRight(scale).longValueExact();
        } catch (ArithmeticException e) {
            throw new Rejection(FailureReason.AMOUNT_TOO_LARGE);
        }
        if (minor > maxMinor) {
            throw new Rejection(FailureReason.AMOUNT_TOO_LARGE);
        }
        return minor;
    }

    public BigDecimal fromMinor(long minor) {
        return BigDecimal.valueOf(minor, scale);
    }

    public static BigDecimal fromMinor(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale);
    }

    public CurrencyDefinition withEnabled(boolean value) {
        return new CurrencyDefinition(id, displayName, symbol, value, scale, startingBalance(), maxBalance(), transferEnabled, format);
    }

    public long startingMinor() {
        return startingMinor;
    }

    public long maxMinor() {
        return maxMinor;
    }

    public String formatTemplate() {
        return format;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String symbol() {
        return symbol;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public int scale() {
        return scale;
    }

    @Override
    public BigDecimal startingBalance() {
        return fromMinor(startingMinor);
    }

    @Override
    public BigDecimal maxBalance() {
        return fromMinor(maxMinor);
    }

    @Override
    public boolean transferEnabled() {
        return transferEnabled;
    }

    @Override
    public String format(BigDecimal amount) {
        return format.replace("{amount}", formatNumber(amount))
                .replace("{symbol}", symbol)
                .replace("{name}", displayName)
                .replace("{id}", id);
    }

    /** Grouped number with exactly {@code scale} decimals. Display only; values are never stored from this. */
    public String formatNumber(BigDecimal amount) {
        if (amount == null) {
            return "?";
        }
        DecimalFormat df = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        df.setRoundingMode(RoundingMode.DOWN);
        return df.format(amount);
    }

    @Override
    public String toString() {
        return "Currency[" + id + ", scale=" + scale + ", enabled=" + enabled + ", transfer=" + transferEnabled + "]";
    }
}
