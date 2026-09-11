package me.cupjok.multicurrency;

import me.cupjok.multicurrency.bukkit.config.CurrencyConfigLoader;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static me.cupjok.multicurrency.TestSupport.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyConfigLoaderTest {

    private static CurrencyConfigLoader.Result load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return CurrencyConfigLoader.load(cfg.getConfigurationSection("currencies"));
    }

    @Test
    void parsesValidCurrenciesAndDefaultsTransferToDisabled() throws Exception {
        CurrencyConfigLoader.Result r = load("""
                currencies:
                  coins:
                    display-name: Coins
                    symbol: "C"
                    decimals: 2
                    starting-balance: 10.5
                    transfer-enabled: true
                  event_points:
                    display-name: Event Points
                """);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        Map<String, CurrencyDefinition> byId = r.currencies().stream().collect(Collectors.toMap(CurrencyDefinition::id, Function.identity()));
        assertTrue(byId.get("coins").transferEnabled());
        assertEquals(d("10.50"), byId.get("coins").startingBalance());
        CurrencyDefinition ep = byId.get("event_points");
        assertFalse(ep.transferEnabled(), "transfer must default to false (fail closed)");
        assertTrue(ep.enabled());
        assertEquals(0, ep.scale());
    }

    @Test
    void invalidCurrenciesAreSkippedAndReported() throws Exception {
        CurrencyConfigLoader.Result r = load("""
                currencies:
                  Bad-Id:
                    symbol: x
                  toomany:
                    decimals: 2
                    starting-balance: "1.005"
                  notnumber:
                    starting-balance: "abc"
                  overmax:
                    starting-balance: 20
                    max-balance: 10
                  scale:
                    decimals: 12
                  fraction:
                    decimals: 1.5
                  good:
                    transfer-enabled: false
                """);
        assertEquals(1, r.currencies().size());
        assertEquals("good", r.currencies().getFirst().id());
        assertEquals(6, r.errors().size(), r.errors().toString());
    }

    @Test
    void missingSectionIsReported() {
        CurrencyConfigLoader.Result r = CurrencyConfigLoader.load(null);
        assertTrue(r.currencies().isEmpty());
        assertEquals(1, r.errors().size());
    }

    @Test
    void bundledConfigLoadsCleanly() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            cfg.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        CurrencyConfigLoader.Result r = CurrencyConfigLoader.load(cfg.getConfigurationSection("currencies"));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        Map<String, CurrencyDefinition> byId = r.currencies().stream().collect(Collectors.toMap(CurrencyDefinition::id, Function.identity()));
        assertTrue(byId.get("coins").transferEnabled());
        assertFalse(byId.get("gems").transferEnabled());
        assertFalse(byId.get("event_points").transferEnabled());
        assertTrue(byId.get("tokens").transferEnabled());
    }
}
