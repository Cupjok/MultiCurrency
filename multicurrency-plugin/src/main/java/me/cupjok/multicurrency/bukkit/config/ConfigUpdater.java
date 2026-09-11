package me.cupjok.multicurrency.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds settings that a newer plugin version introduced to an existing {@code config.yml}.
 *
 * <p>Only missing keys are added, with their default value and comments. Existing values are never
 * changed or removed. The {@code currencies} section is never touched: the bundled example
 * currencies must not reappear in a server's economy after an update, and a currency the admin
 * deleted must stay deleted.
 */
public final class ConfigUpdater {

    public static final String VERSION_KEY = "config-version";
    private static final String CURRENCIES = "currencies";

    private ConfigUpdater() {
    }

    /**
     * Merges missing keys from {@code defaults} into {@code user} (which must be loaded without defaults).
     *
     * @return the paths that were added or updated; empty when the file is already current
     */
    public static List<String> merge(YamlConfiguration user, YamlConfiguration defaults) {
        List<String> changed = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (path.equals(CURRENCIES) || path.startsWith(CURRENCIES + ".")) {
                continue;
            }
            if (user.contains(path) || blockedByScalarParent(user, path)) {
                continue;
            }
            if (defaults.isConfigurationSection(path)) {
                user.createSection(path);
            } else {
                user.set(path, defaults.get(path));
            }
            user.setComments(path, defaults.getComments(path));
            user.setInlineComments(path, defaults.getInlineComments(path));
            changed.add(path);
        }
        int current = user.getInt(VERSION_KEY, 0);
        int latest = defaults.getInt(VERSION_KEY, 0);
        if (current < latest) {
            user.set(VERSION_KEY, latest);
            if (!changed.contains(VERSION_KEY)) {
                changed.add(VERSION_KEY);
            }
        }
        return changed;
    }

    /** An admin replaced a section with a plain value: adding children would overwrite that value. */
    private static boolean blockedByScalarParent(ConfigurationSection user, String path) {
        int dot = path.indexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            if (user.contains(parent) && !user.isConfigurationSection(parent)) {
                return true;
            }
            dot = path.indexOf('.', dot + 1);
        }
        return false;
    }
}
