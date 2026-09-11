package me.cupjok.multicurrency.bukkit;

import me.cupjok.multicurrency.api.BalanceEntry;
import me.cupjok.multicurrency.api.MultiCurrencyApi;
import me.cupjok.multicurrency.api.MultiCurrencyProvider;
import me.cupjok.multicurrency.bukkit.command.CurrencyCommand;
import me.cupjok.multicurrency.bukkit.config.ConfigException;
import me.cupjok.multicurrency.bukkit.config.ConfigStore;
import me.cupjok.multicurrency.bukkit.config.ConfigUpdater;
import me.cupjok.multicurrency.bukkit.config.CurrencyConfigLoader;
import me.cupjok.multicurrency.bukkit.editor.CurrencyEditor;
import me.cupjok.multicurrency.bukkit.editor.EditorGui;
import me.cupjok.multicurrency.bukkit.papi.PlaceholderHook;
import me.cupjok.multicurrency.core.cache.AsyncCache;
import me.cupjok.multicurrency.core.cache.BalanceKey;
import me.cupjok.multicurrency.core.currency.CurrencyRegistry;
import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/**
 * Entry point: wiring and lifecycle only. No balance logic lives here.
 *
 * <p>MultiCurrency does not register a Vault economy and never touches the primary economy (CMI).
 */
public final class MultiCurrencyPlugin extends JavaPlugin implements Listener {

    private static final int MAX_CONFIG_BACKUPS = 20;
    private static final long CACHE_IDLE_MILLIS = 10 * 60_000L;

    private CurrencyService service;
    private volatile Messages messages;
    private volatile YamlConfiguration config;
    private ConfigStore configStore;
    private volatile AsyncCache<BalanceKey, BigDecimal> balanceCache;
    private volatile AsyncCache<String, List<BalanceEntry>> topCache;
    private EditorGui editorGui;
    private PlaceholderHook placeholderHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configStore = new ConfigStore(getDataFolder().toPath().resolve("config.yml"), getDataFolder().toPath().resolve("backups"),
                MAX_CONFIG_BACKUPS);

        String content;
        YamlConfiguration raw;
        try {
            content = updateConfigFile();
            raw = parse(content);
        } catch (IOException | InvalidConfigurationException e) {
            // Fail closed: starting with default settings could point at an empty SQLite file instead of the real database.
            getLogger().log(Level.SEVERE, "config.yml cannot be read or is not valid YAML; MultiCurrency is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CurrencyRegistry registry;
        Database database;
        boolean sqlite;
        try {
            applyConfig(raw, content);
            registry = loadCurrencies(raw, false);
            String type = config.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
            sqlite = type.equals("sqlite");
            database = createDatabase(config, type);
        } catch (RuntimeException | ConfigException e) {
            getLogger().log(Level.SEVERE, "Invalid configuration; MultiCurrency is disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        int threads = sqlite ? 1 : Math.max(1, config.getInt("storage.mariadb.pool-size", 8));
        service = new CurrencyService(database, config.getString("server-id", "server-1"), threads, getLogger());
        service.setBalanceChangeListener((player, currency) -> {
            AsyncCache<BalanceKey, BigDecimal> cache = balanceCache;
            if (cache != null) {
                cache.invalidate(new BalanceKey(player, currency));
            }
        });
        createCaches();
        service.start(registry).whenComplete((v, err) -> {
            if (err != null) {
                runSync(() -> {
                    getLogger().severe("Disabling MultiCurrency: storage could not be initialised (fail closed).");
                    getServer().getPluginManager().disablePlugin(this);
                });
            } else {
                getLogger().info("MultiCurrency ready with " + service.registry().all().size() + " currencies ("
                        + (sqlite ? "SQLite" : "MariaDB/MySQL") + ").");
            }
        });

        MultiCurrencyProvider.register(service);
        getServer().getServicesManager().register(MultiCurrencyApi.class, service, this, ServicePriority.Normal);

        editorGui = new EditorGui(this, new CurrencyEditor(this));
        CurrencyCommand command = new CurrencyCommand(this);
        for (String name : new String[]{"currency", "cbal", "cpay"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(command);
                pc.setTabCompleter(command);
            }
        }
        getServer().getPluginManager().registerEvents(new PlayerNameListener(service), this);
        getServer().getPluginManager().registerEvents(editorGui, this);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            AsyncCache<BalanceKey, BigDecimal> b = balanceCache;
            AsyncCache<String, List<BalanceEntry>> t = topCache;
            if (b != null) {
                b.sweep(CACHE_IDLE_MILLIS);
            }
            if (t != null) {
                t.sweep(CACHE_IDLE_MILLIS);
            }
        }, 1200L, 1200L);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderHook = PlaceholderHook.register(this);
        }
    }

    @Override
    public void onDisable() {
        if (editorGui != null) {
            // Our menus hold display items; once the listener is gone they must not stay open.
            editorGui.closeAll();
        }
        if (placeholderHook != null) {
            placeholderHook.unregister();
            placeholderHook = null;
        }
        if (service != null) {
            getServer().getServicesManager().unregister(MultiCurrencyApi.class, service);
            MultiCurrencyProvider.unregister(service);
            service.close();
            service = null;
        }
    }

    // ================================================================== configuration

    /**
     * Adds settings introduced by a newer plugin version to the existing file. Existing values and the
     * currencies section are never changed; the previous file is kept in {@code backups/}.
     */
    private String updateConfigFile() throws IOException, InvalidConfigurationException {
        String content = configStore.read();
        YamlConfiguration user = parse(content);
        YamlConfiguration defaults = bundledDefaults();
        if (defaults == null) {
            return content;
        }
        List<String> added = ConfigUpdater.merge(user, defaults);
        if (added.isEmpty()) {
            return content;
        }
        String updated = user.saveToString();
        parse(updated);
        Path backup = configStore.write(updated, "update");
        // Name only the top-most new entries; their children came with them.
        List<String> roots = added.stream()
                .filter(p -> added.stream().noneMatch(other -> p.startsWith(other + ".")))
                .toList();
        getLogger().info("config.yml updated with " + added.size() + " new setting(s) in: " + String.join(", ", roots)
                + ". Your existing settings were kept. Previous file: " + (backup == null ? "-" : backup.getFileName()));
        return updated;
    }

    private static YamlConfiguration parse(String content) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return yaml;
    }

    private YamlConfiguration bundledDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** Makes {@code raw} the active configuration. Missing messages and settings fall back to the bundled defaults. */
    private void applyConfig(YamlConfiguration raw, String content) {
        YamlConfiguration withDefaults = new YamlConfiguration();
        try {
            withDefaults.loadFromString(content);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        YamlConfiguration defaults = bundledDefaults();
        if (defaults != null) {
            withDefaults.setDefaults(defaults);
        }
        this.config = withDefaults;
        this.messages = new Messages(withDefaults.getConfigurationSection("messages"));
        configStore.markLoaded(content);
    }

    /**
     * Validates the currencies. Uses the file without bundled defaults, so a missing key of an example
     * id never inherits the example's value.
     *
     * @param strict {@code true} on reload: any invalid currency refuses the whole reload
     */
    private CurrencyRegistry loadCurrencies(YamlConfiguration raw, boolean strict) throws ConfigException {
        CurrencyConfigLoader.Result result = CurrencyConfigLoader.load(raw.getConfigurationSection("currencies"));
        for (String error : result.errors()) {
            getLogger().severe("Config: " + error);
        }
        if (strict && !result.errors().isEmpty()) {
            throw new ConfigException(result.errors());
        }
        return new CurrencyRegistry(result.currencies());
    }

    /**
     * Re-reads {@code config.yml} and applies currencies and messages. Storage settings require a
     * restart. If any currency is invalid, nothing is applied and the previous configuration stays
     * active: the future fails with {@link ConfigException}.
     */
    public CompletableFuture<CurrencyRegistry> reloadCurrencies() {
        if (service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("MultiCurrency is not enabled"));
        }
        try {
            String content = configStore.read();
            YamlConfiguration raw = parse(content);
            CurrencyRegistry registry = loadCurrencies(raw, true);
            applyConfig(raw, content);
            createCaches();
            return service.reload(registry);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be read: " + e.getMessage());
            return CompletableFuture.failedFuture(new ConfigException(List.of("config.yml is not valid YAML: " + firstLine(e.getMessage()))));
        } catch (ConfigException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Prints the outcome of {@link #reloadCurrencies()} to {@code sender}. Main thread. */
    public void sendReloadResult(CommandSender sender, CurrencyRegistry registry, Throwable err) {
        if (err == null) {
            messages.send(sender, "reload.done", ph("count", registry.all().size()),
                    ph("conflicts", String.join(", ", service.conflictedCurrencies())));
            return;
        }
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (cause instanceof ConfigException ce) {
            messages.send(sender, "reload.errors", ph("count", ce.errors().size()));
            ce.errors().stream().limit(5).forEach(e -> messages.send(sender, "reload.error-line", ph("error", e)));
        } else {
            messages.send(sender, "reload.failed");
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private void createCaches() {
        long balanceTtl = Math.clamp(config.getLong("placeholders.balance-cache-seconds", 5), 1, 300) * 1000L;
        long topTtl = Math.clamp(config.getLong("placeholders.top-cache-seconds", 30), 5, 3600) * 1000L;
        CurrencyService s = service;
        balanceCache = new AsyncCache<>(k -> s.balance(k.player(), k.currency()), balanceTtl, 50_000, null);
        topCache = new AsyncCache<>(id -> s.top(id, PlaceholderHook.TOP_SIZE, 0), topTtl, 1_000, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AsyncCache<BalanceKey, BigDecimal> cache = balanceCache;
        if (cache != null) {
            UUID id = event.getPlayer().getUniqueId();
            cache.removeIf(k -> k.player().equals(id));
        }
    }

    private Database createDatabase(FileConfiguration cfg, String type) {
        String prefix = cfg.getString("storage.table-prefix", "mc_");
        switch (type) {
            case "sqlite" -> {
                Path dataDir = getDataFolder().toPath().toAbsolutePath().normalize();
                Path file = dataDir.resolve(cfg.getString("storage.sqlite.file", "multicurrency.db")).normalize();
                if (!file.startsWith(dataDir)) {
                    throw new IllegalArgumentException("storage.sqlite.file must stay inside the plugin folder");
                }
                return Database.sqlite(file, prefix, Bukkit::isPrimaryThread, getLogger());
            }
            case "mariadb", "mysql" -> {
                ConfigurationSection m = cfg.getConfigurationSection("storage.mariadb");
                if (m == null) {
                    throw new IllegalArgumentException("storage.mariadb section is missing");
                }
                Map<String, String> props = new LinkedHashMap<>();
                ConfigurationSection p = m.getConfigurationSection("properties");
                if (p != null) {
                    for (String key : p.getKeys(false)) {
                        props.put(key, String.valueOf(p.get(key)));
                    }
                }
                return Database.mariadb(m.getString("host", "127.0.0.1"), m.getInt("port", 3306),
                        m.getString("database", "multicurrency"), m.getString("username", "multicurrency"),
                        m.getString("password", ""), m.getInt("pool-size", 8), props, prefix, Bukkit::isPrimaryThread, getLogger());
            }
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mariadb or mysql (was '" + type + "')");
        }
    }

    // ================================================================== accessors

    /** The active configuration (with bundled defaults for missing messages and settings). */
    @Override
    public FileConfiguration getConfig() {
        YamlConfiguration c = config;
        return c != null ? c : super.getConfig();
    }

    /** Runs on the main thread if the plugin is still enabled. */
    public void runSync(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(this, task);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // Plugin is being disabled.
            }
        }
    }

    public CurrencyService service() {
        return service;
    }

    public Messages messages() {
        return messages;
    }

    public ConfigStore configStore() {
        return configStore;
    }

    public EditorGui editorGui() {
        return editorGui;
    }

    public AsyncCache<BalanceKey, BigDecimal> balanceCache() {
        return balanceCache;
    }

    public AsyncCache<String, List<BalanceEntry>> topCache() {
        return topCache;
    }
}
