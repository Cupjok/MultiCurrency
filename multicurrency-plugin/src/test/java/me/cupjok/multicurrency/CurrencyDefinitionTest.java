package me.cupjok.multicurrency;

import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.core.Rejection;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyDefinitionTest {

    private final CurrencyDefinition coins = new CurrencyDefinition("coins", "Coins", "C", true, 2, d("0"), null, true, "{symbol}{amount}");
    private final CurrencyDefinition gems = new CurrencyDefinition("gems", "Gems", "G", true, 0, d("0"), d("1000"), false, null);

    private static FailureReason reject(CurrencyDefinition c, BigDecimal amount, boolean allowZero) {
        return assertThrows(Rejection.class, () -> c.toMinor(amount, allowZero)).reason();
    }

    @Test
    void exactConversion() throws Rejection {
        assertEquals(1234, coins.toMinor(d("12.34"), false));
        assertEquals(1230, coins.toMinor(d("12.3"), false));
        assertEquals(1200, coins.toMinor(d("12.000000"), false), "trailing zeros are not extra precision");
        assertEquals(new BigDecimal("12.34"), coins.fromMinor(1234));
        assertEquals(0, coins.toMinor(d("0"), true));
    }

    @Test
    void rejectsExcessPrecisionInsteadOfRounding() {
        assertEquals(FailureReason.INVALID_PRECISION, reject(coins, d("0.001"), false));
        assertEquals(FailureReason.INVALID_PRECISION, reject(coins, d("1.005"), false));
        assertEquals(FailureReason.INVALID_PRECISION, reject(gems, d("1.5"), false));
        assertEquals(FailureReason.INVALID_PRECISION, reject(gems, new BigDecimal("1E-1000000000"), false));
    }

    @Test
    void rejectsZeroNegativeAndNull() {
        assertEquals(FailureReason.INVALID_AMOUNT, reject(coins, d("0"), false));
        assertEquals(FailureReason.INVALID_AMOUNT, reject(coins, d("0.00"), false));
        assertEquals(FailureReason.INVALID_AMOUNT, reject(coins, d("-1"), false));
        assertEquals(FailureReason.INVALID_AMOUNT, reject(coins, d("-1"), true));
        assertEquals(FailureReason.INVALID_AMOUNT, reject(coins, null, false));
    }

    @Test
    void rejectsOverflowAndHugeValues() {
        assertEquals(FailureReason.AMOUNT_TOO_LARGE, reject(coins, new BigDecimal("1E+1000000000"), false));
        assertEquals(FailureReason.AMOUNT_TOO_LARGE, reject(coins, d("92233720368547758.08"), false), "Long.MAX_VALUE + 1 minor");
        assertEquals(FailureReason.AMOUNT_TOO_LARGE, reject(coins, d("99999999999999999999"), false));
        assertEquals(FailureReason.AMOUNT_TOO_LARGE, reject(gems, d("1001"), false), "above max-balance");
    }

    @Test
    void maxRepresentableValue() throws Rejection {
        assertEquals(Long.MAX_VALUE, coins.toMinor(d("92233720368547758.07"), false));
    }

    @Test
    void invalidDefinitionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("Coins", null, null, true, 0, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("a b", null, null, true, 0, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 9, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, -1, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 0, d("0.5"), null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 0, d("-1"), null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 0, d("11"), d("10"), false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 0, null, d("0"), false, null));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyDefinition("x", null, null, true, 8, null, d("1000000000000"), false, null));
    }

    @Test
    void formatting() {
        assertEquals("C1,234.50", coins.format(d("1234.5")));
        assertEquals("C0.00", coins.format(d("0")));
        assertEquals("G1,000", gems.format(d("1000")));
    }

    @Test
    void registryLookupIsCaseInsensitiveAndRejectsDuplicates() {
        CurrencyRegistry r = new CurrencyRegistry(List.of(coins, gems));
        assertTrue(r.find("COINS").isPresent());
        assertTrue(r.find(" gems ").isPresent());
        assertTrue(r.find("nope").isEmpty());
        assertTrue(r.find(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRegistry(List.of(coins, coins)));
    }
}
