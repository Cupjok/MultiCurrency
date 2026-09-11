package me.cupjok.multicurrency.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MiniMessage-backed messages from {@code config.yml}. Dynamic values (names, reasons, amounts) are
 * always inserted with {@link Placeholder#unparsed}, so they can never inject formatting or click events.
 */
public final class Messages {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final ConfigurationSection section;
    private final String prefix;

    public Messages(ConfigurationSection section) {
        this.section = section;
        this.prefix = section == null ? "" : section.getString("prefix", "");
    }

    public Component get(String key, TagResolver... placeholders) {
        String raw = section == null ? null : section.getString(key);
        if (raw == null) {
            raw = "<red>Missing message: " + key;
        }
        List<TagResolver> all = new ArrayList<>(List.of(placeholders));
        all.add(Placeholder.parsed("prefix", prefix));
        return mini.deserialize(raw, TagResolver.resolver(all));
    }

    /** Multi-line message as one component per line (item lore). Missing keys give no lines. */
    public List<Component> lines(String key, TagResolver... placeholders) {
        String raw = section == null ? null : section.getString(key);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<TagResolver> all = new ArrayList<>(List.of(placeholders));
        all.add(Placeholder.parsed("prefix", prefix));
        TagResolver resolver = TagResolver.resolver(all);
        List<Component> out = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            out.add(mini.deserialize(line, resolver));
        }
        return out;
    }

    public void send(CommandSender to, String key, TagResolver... placeholders) {
        to.sendMessage(get(key, placeholders));
    }

    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** {@code INSUFFICIENT_FUNDS} to {@code error.insufficient-funds}. */
    public static String errorKey(Enum<?> reason) {
        return "error." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
