package me.cupjok.multicurrency.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Who initiated a transaction. Recorded in the audit ledger.
 *
 * @param type kind of initiator
 * @param id   stable identifier: player UUID, plugin name, or a fixed token for console/system
 * @param name human-readable name at the time of the transaction
 */
public record Actor(Type type, String id, String name) {

    public enum Type { PLAYER, CONSOLE, PLUGIN, SYSTEM }

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("actor id must be 1-64 characters");
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
    }

    public static Actor player(UUID uuid, String name) {
        return new Actor(Type.PLAYER, uuid.toString(), name);
    }

    public static Actor console() {
        return new Actor(Type.CONSOLE, "console", "Console");
    }

    /** Use your plugin's name, e.g. {@code Actor.plugin("DynamicShop3")}. */
    public static Actor plugin(String pluginName) {
        return new Actor(Type.PLUGIN, pluginName, pluginName);
    }

    public static Actor system(String component) {
        return new Actor(Type.SYSTEM, component, component);
    }
}
