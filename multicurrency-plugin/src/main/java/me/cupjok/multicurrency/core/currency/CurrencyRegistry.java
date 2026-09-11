package me.cupjok.multicurrency.core.currency;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Immutable snapshot of the configured currencies. Replaced atomically on reload. */
public final class CurrencyRegistry {

    private final Map<String, CurrencyDefinition> byId;

    public CurrencyRegistry(List<CurrencyDefinition> currencies) {
        Map<String, CurrencyDefinition> map = new LinkedHashMap<>();
        for (CurrencyDefinition c : currencies) {
            if (map.putIfAbsent(c.id(), c) != null) {
                throw new IllegalArgumentException("duplicate currency id '" + c.id() + "'");
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    public static CurrencyRegistry empty() {
        return new CurrencyRegistry(List.of());
    }

    public Optional<CurrencyDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(normalize(id)));
    }

    public Collection<CurrencyDefinition> all() {
        return byId.values();
    }

    public static String normalize(String id) {
        return id == null ? null : id.trim().toLowerCase(Locale.ROOT);
    }
}
