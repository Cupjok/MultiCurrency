package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.bukkit.Messages;
import me.cupjok.multicurrency.bukkit.MultiCurrencyPlugin;
import me.cupjok.multicurrency.bukkit.config.ConfigStore;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.InvalidInput;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/**
 * Applies in-game currency edits to {@code config.yml}, then reloads through the normal path.
 *
 * <p>Safety rules, in order:
 * <ol>
 *   <li>The permission is checked again at the moment of the change, not only when a menu opened.</li>
 *   <li>The file on disk must be the one that is currently loaded. Manual edits that were saved but not
 *       reloaded are never overwritten or applied behind the admin's back.</li>
 *   <li>The edited file must pass the same validation as {@code /currency reload}; otherwise nothing is written.</li>
 *   <li>The previous file is backed up and the new one replaces it atomically.</li>
 *   <li>Every change is logged to the console and to {@code editor-audit.log}.</li>
 * </ol>
 * Balance rules (scale lock, limits, transfer switch) stay in the service; the editor only changes
 * configuration, and the reload applies the service's checks to it.
 */
public final class CurrencyEditor {

    public static final String PERM_EDITOR = "multicurrency.admin.editor";
    public static final String PERM_ECONOMY = "multicurrency.admin.editor.economy";
    public static final String PERM_CREATE = "multicurrency.admin.editor.create";
    public static final String PERM_DELETE = "multicurrency.admin.editor.delete";

    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** One change to the loaded YAML. */
    @FunctionalInterface
    public interface Edit {
        void apply(YamlConfiguration yaml) throws InvalidInput;
    }

    private final MultiCurrencyPlugin plugin;
    private final Path auditLog;

    public CurrencyEditor(MultiCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.auditLog = plugin.getDataFolder().toPath().resolve("editor-audit.log");
    }

    /**
     * Validates and writes the edit, then reloads. Main thread only.
     *
     * @param onSuccess runs on the main thread after the reload applied the change
     * @return {@code true} if the file was written; the reload result is reported to the admin later
     */
    public boolean apply(Player admin, String permission, String description, Edit edit, Runnable onSuccess) {
        Messages msg = plugin.messages();
        if (!admin.hasPermission(permission)) {
            msg.send(admin, "no-permission");
            return false;
        }
        ConfigStore store = plugin.configStore();
        String content;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            content = store.read();
            if (!store.isLoaded(content)) {
                msg.send(admin, "editor.changed-on-disk");
                return false;
            }
            yaml.loadFromString(content);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "Editor could not read config.yml", e);
            msg.send(admin, "editor.save-failed");
            return false;
        }
        try {
            edit.apply(yaml);
        } catch (InvalidInput e) {
            msg.send(admin, "editor.invalid." + e.reason(), ph("value", e.getMessage() == null ? "" : e.getMessage()));
            return false;
        }
        List<String> errors = CurrencyYaml.validate(yaml).errors();
        if (!errors.isEmpty()) {
            msg.send(admin, "editor.invalid.config", ph("error", errors.getFirst()));
            return false;
        }
        try {
            store.write(yaml.saveToString(), "editor");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Editor could not write config.yml; nothing was changed", e);
            msg.send(admin, "editor.save-failed");
            return false;
        }
        audit(admin, description);
        plugin.reloadCurrencies().whenComplete((registry, err) -> plugin.runSync(() -> {
            if (!admin.isOnline()) {
                return;
            }
            if (err != null) {
                plugin.sendReloadResult(admin, registry, err);
                return;
            }
            plugin.messages().send(admin, "editor.saved", ph("change", description));
            if (!plugin.service().conflictedCurrencies().isEmpty()) {
                plugin.sendReloadResult(admin, registry, null);
            }
            onSuccess.run();
        }));
        return true;
    }

    private void audit(Player admin, String description) {
        String line = OffsetDateTime.now().format(AUDIT_TIME) + " " + admin.getName() + " (" + admin.getUniqueId() + "): " + description;
        plugin.getLogger().info("[Editor] " + line);
        try {
            Files.writeString(auditLog, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write editor-audit.log", e);
        }
    }
}
