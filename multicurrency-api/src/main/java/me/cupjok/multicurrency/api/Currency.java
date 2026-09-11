package me.cupjok.multicurrency.api;

import java.math.BigDecimal;

/** Immutable view of one configured currency. */
public interface Currency {

    /** Stable internal id ({@code [a-z0-9_]{1,32}}). Stored in the database; never rename it. */
    String id();

    String displayName();

    String symbol();

    boolean enabled();

    /** Number of decimal places. Fixed once the currency has been used. */
    int scale();

    BigDecimal startingBalance();

    /** Largest balance an account may hold. */
    BigDecimal maxBalance();

    /** Whether players may transfer this currency to each other. */
    boolean transferEnabled();

    /** Formats an amount for display using this currency's format, symbol and scale. */
    String format(BigDecimal amount);
}
