package me.cupjok.multicurrency.bukkit.config;

import java.util.List;

/** {@code config.yml} could not be read or contains invalid currencies. Nothing was applied. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;
    private final List<String> errors;

    public ConfigException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
