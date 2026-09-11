package me.cupjok.multicurrency;

import me.cupjok.multicurrency.bukkit.config.ConfigStore;
import me.cupjok.multicurrency.bukkit.config.ConfigUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    @TempDir
    Path dir;

    private static YamlConfiguration yaml(String s) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(s);
        return y;
    }

    private static final String DEFAULTS = """
            config-version: 3
            server-id: "server-1"
            # How long placeholder balances are cached.
            placeholders:
              balance-cache-seconds: 5
              loading: "..."
            storage:
              type: sqlite
              mariadb:
                properties: {}
            currencies:
              coins:
                decimals: 2
              gems:
                decimals: 0
            messages:
              prefix: "[C] "
              new-message: "hello"
              editor:
                title: "Editor"
            """;

    @Test
    void addsOnlyMissingKeysAndKeepsAdminValues() throws Exception {
        YamlConfiguration user = yaml("""
                config-version: 1
                # my server
                server-id: "lobby"
                storage:
                  type: mariadb
                currencies:
                  tokens:
                    decimals: 0
                messages:
                  prefix: "<red>[Mine] "
                """);
        List<String> changed = ConfigUpdater.merge(user, yaml(DEFAULTS));

        assertEquals("lobby", user.getString("server-id"));
        assertEquals("mariadb", user.getString("storage.type"));
        assertEquals("<red>[Mine] ", user.getString("messages.prefix"));
        assertEquals(5, user.getInt("placeholders.balance-cache-seconds"));
        assertEquals("hello", user.getString("messages.new-message"));
        assertEquals("Editor", user.getString("messages.editor.title"));
        assertTrue(user.isConfigurationSection("storage.mariadb.properties"));
        assertEquals(3, user.getInt("config-version"));
        assertTrue(changed.contains("placeholders"));
        assertTrue(changed.contains("config-version"));
        assertEquals(List.of("How long placeholder balances are cached."), user.getComments("placeholders"));
    }

    @Test
    void neverTouchesCurrencies() throws Exception {
        YamlConfiguration user = yaml("""
                currencies:
                  tokens:
                    decimals: 0
                """);
        ConfigUpdater.merge(user, yaml(DEFAULTS));
        assertEquals(List.of("tokens"), List.copyOf(user.getConfigurationSection("currencies").getKeys(false)),
                "deleted or example currencies must not come back");

        YamlConfiguration empty = yaml("currencies: {}\n");
        ConfigUpdater.merge(empty, yaml(DEFAULTS));
        assertTrue(empty.getConfigurationSection("currencies").getKeys(false).isEmpty());

        YamlConfiguration none = yaml("server-id: x\n");
        ConfigUpdater.merge(none, yaml(DEFAULTS));
        assertFalse(none.contains("currencies"));
    }

    @Test
    void doesNotOverwriteAScalarThatReplacedASection() throws Exception {
        YamlConfiguration user = yaml("placeholders: false\n");
        ConfigUpdater.merge(user, yaml(DEFAULTS));
        assertFalse(user.getBoolean("placeholders"));
        assertFalse(user.isConfigurationSection("placeholders"));
    }

    @Test
    void isIdempotentAndSurvivesASaveRoundTrip() throws Exception {
        YamlConfiguration user = yaml("server-id: a\n");
        assertFalse(ConfigUpdater.merge(user, yaml(DEFAULTS)).isEmpty());
        YamlConfiguration reloaded = yaml(user.saveToString());
        assertTrue(ConfigUpdater.merge(reloaded, yaml(DEFAULTS)).isEmpty());
        assertEquals("a", reloaded.getString("server-id"));
    }

    @Test
    void bundledConfigIsAlreadyComplete() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            bundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        YamlConfiguration copy = yaml(bundled.saveToString());
        assertTrue(ConfigUpdater.merge(copy, bundled).isEmpty());
        assertTrue(bundled.getInt(ConfigUpdater.VERSION_KEY) >= 2);
    }

    @Test
    void storeWritesAtomicallyKeepsBackupsAndDetectsUnreloadedEdits() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 1\n");
        ConfigStore store = new ConfigStore(file, dir.resolve("backups"), 3);
        assertFalse(store.isLoaded(store.read()));
        store.markLoaded(store.read());
        assertTrue(store.isLoaded(store.read()));

        Path backup = store.write("a: 2\n", "editor");
        assertNotNull(backup);
        assertEquals("a: 1\n", Files.readString(backup));
        assertEquals("a: 2\n", store.read());
        assertTrue(store.isLoaded(store.read()), "own write counts as loaded");

        Files.writeString(file, "a: 3\n");
        assertFalse(store.isLoaded(store.read()), "manual edit that was not reloaded is detected");

        for (int i = 0; i < 6; i++) {
            store.write("a: " + (10 + i) + "\n", "x");
        }
        try (Stream<Path> s = Files.list(dir.resolve("backups"))) {
            assertEquals(3, s.count(), "only the newest backups are kept");
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temp file left behind");
        }
        assertNull(new ConfigStore(dir.resolve("new.yml"), dir.resolve("b2"), 3).write("x: 1\n", "first"));
    }
}
