package me.cupjok.multicurrency;

import me.cupjok.multicurrency.bukkit.config.CurrencyConfigLoader;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.Field;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.InvalidInput;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyYamlTest {

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (var in = CurrencyYamlTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return y;
    }

    private static YamlConfiguration roundTrip(YamlConfiguration y) throws Exception {
        YamlConfiguration out = new YamlConfiguration();
        out.loadFromString(y.saveToString());
        return out;
    }

    private static CurrencyDefinition find(YamlConfiguration y, String id) {
        CurrencyConfigLoader.Result r = CurrencyYaml.validate(y);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        return r.currencies().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void createAddsADisabledCurrencyThatLoads() throws Exception {
        YamlConfiguration y = bundled();
        CurrencyYaml.create(y, " Shards ", 1);
        y = roundTrip(y);
        CurrencyDefinition c = find(y, "shards");
        assertFalse(c.enabled(), "new currencies start disabled so decimals are not locked yet");
        assertFalse(c.transferEnabled());
        assertEquals(1, c.scale());
        assertEquals(0, c.startingBalance().signum());
        assertEquals(5, CurrencyYaml.ids(y).size());
        assertEquals("shards", List.copyOf(CurrencyYaml.ids(y)).getLast());
    }

    @Test
    void createRefusesDuplicatesAndBadIds() throws Exception {
        YamlConfiguration y = bundled();
        assertEquals("exists", assertThrows(InvalidInput.class, () -> CurrencyYaml.create(y, "COINS", 0)).reason());
        assertEquals("id", assertThrows(InvalidInput.class, () -> CurrencyYaml.create(y, "bad-id", 0)).reason());
        assertEquals("id", assertThrows(InvalidInput.class, () -> CurrencyYaml.create(y, "", 0)).reason());
        assertEquals("id", assertThrows(InvalidInput.class, () -> CurrencyYaml.create(y, "a".repeat(33), 0)).reason());
        assertEquals("decimals", assertThrows(InvalidInput.class, () -> CurrencyYaml.create(y, "x", 9)).reason());
    }

    @Test
    void fieldEditsSurviveSaveAndKeepOtherCurrencies() throws Exception {
        YamlConfiguration y = bundled();
        CurrencyYaml.set(y, "gems", Field.SYMBOL, CurrencyYaml.parseText(Field.SYMBOL, "✦"));
        CurrencyYaml.set(y, "gems", Field.DISPLAY_NAME, CurrencyYaml.parseText(Field.DISPLAY_NAME, "  Shiny Gems "));
        CurrencyYaml.set(y, "gems", Field.STARTING, CurrencyYaml.parseText(Field.STARTING, "25"));
        CurrencyYaml.set(y, "gems", Field.MAX, CurrencyYaml.parseText(Field.MAX, "1000"));
        CurrencyYaml.set(y, "gems", Field.TRANSFER, true);
        y = roundTrip(y);
        CurrencyDefinition gems = find(y, "gems");
        assertEquals("✦", gems.symbol());
        assertEquals("Shiny Gems", gems.displayName());
        assertEquals(d("25"), gems.startingBalance());
        assertEquals(d("1000"), gems.maxBalance());
        assertTrue(gems.transferEnabled());
        assertEquals("🪙", find(y, "coins").symbol(), "emoji of another currency is preserved");
        assertEquals(bundled().getString("messages.help"), y.getString("messages.help"), "multi-line messages keep their value");

        CurrencyYaml.set(y, "gems", Field.MAX, CurrencyYaml.parseText(Field.MAX, "none"));
        assertEquals(Long.MAX_VALUE, find(y, "gems").maxMinor());
        CurrencyYaml.set(y, "gems", Field.SYMBOL, CurrencyYaml.parseText(Field.SYMBOL, "none"));
        assertEquals("", find(y, "gems").symbol());
    }

    @Test
    void validationCatchesValuesTheCurrencyCannotRepresent() throws Exception {
        YamlConfiguration y = bundled();
        CurrencyYaml.set(y, "gems", Field.STARTING, CurrencyYaml.parseText(Field.STARTING, "0.5"));
        List<String> errors = CurrencyYaml.validate(y).errors();
        assertEquals(1, errors.size());
        assertTrue(CurrencyYaml.firstError(errors, "gems").contains("gems"));

        YamlConfiguration y2 = bundled();
        CurrencyYaml.set(y2, "gems", Field.STARTING, "50");
        CurrencyYaml.set(y2, "gems", Field.MAX, "10");
        assertFalse(CurrencyYaml.validate(y2).errors().isEmpty(), "starting above max refused");

        YamlConfiguration y3 = bundled();
        CurrencyYaml.set(y3, "coins", Field.STARTING, "10.25");
        assertTrue(CurrencyYaml.validate(y3).errors().isEmpty());
        CurrencyYaml.set(y3, "coins", Field.DECIMALS, 0);
        assertFalse(CurrencyYaml.validate(y3).errors().isEmpty(), "fewer decimals than the starting balance needs is refused");
    }

    @Test
    void deleteRemovesOnlyThatCurrency() throws Exception {
        YamlConfiguration y = bundled();
        CurrencyYaml.delete(y, "tokens");
        y = roundTrip(y);
        assertEquals(Set.of("coins", "gems", "event_points"), CurrencyYaml.ids(y));
        assertEquals("missing", assertThrows(InvalidInput.class, () -> CurrencyYaml.delete(bundled(), "nope")).reason());
        YamlConfiguration all = bundled();
        for (String id : Set.copyOf(CurrencyYaml.ids(all))) {
            CurrencyYaml.delete(all, id);
        }
        all = roundTrip(all);
        assertTrue(CurrencyYaml.ids(all).isEmpty());
        assertTrue(CurrencyYaml.validate(all).errors().isEmpty(), "an empty currencies section is valid");
    }

    @Test
    void unsafeTextIsRejected() {
        for (String bad : List.of("<red>Gems", "<click:run_command:/op me>x", "§cGems", "&cGems", "&xGems", "zero\u200Bwidth", "\u202Eevil", "new\nline",
                "a".repeat(33), "", "   ")) {
            assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.DISPLAY_NAME, bad), bad);
        }
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.SYMBOL, "x".repeat(17)));
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.FORMAT, "{symbol}"));
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.STARTING, "-1"));
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.STARTING, "1e5"));
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.MAX, "1,000"));
        assertThrows(InvalidInput.class, () -> CurrencyYaml.parseText(Field.MAX, "NaN"));
    }

    @Test
    void safeTextIsAccepted() throws Exception {
        assertEquals("Tips & Tricks", CurrencyYaml.parseText(Field.DISPLAY_NAME, "Tips & Tricks"));
        assertEquals("💎", CurrencyYaml.parseText(Field.SYMBOL, "💎"));
        assertEquals("👨‍👩‍👧", CurrencyYaml.parseText(Field.SYMBOL, "👨‍👩‍👧"));
        assertEquals("{amount} {symbol}", CurrencyYaml.parseText(Field.FORMAT, "{amount} {symbol}"));
        assertEquals("0.25", CurrencyYaml.parseText(Field.STARTING, "0.25"));
        assertNull(CurrencyYaml.parseText(Field.MAX, "NONE"));
    }

    @Test
    void editsOfUnknownCurrenciesAreRefused() throws Exception {
        YamlConfiguration y = bundled();
        assertEquals("missing", assertThrows(InvalidInput.class, () -> CurrencyYaml.set(y, "nope", Field.SYMBOL, "x")).reason());
        assertEquals("value", assertThrows(InvalidInput.class, () -> CurrencyYaml.set(y, "coins", Field.SYMBOL, null)).reason());
    }
}
