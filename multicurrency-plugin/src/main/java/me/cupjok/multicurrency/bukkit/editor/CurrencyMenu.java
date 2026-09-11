package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.Field;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.service.CurrencyService.CurrencyStats;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/** Settings of one currency. Stored data (decimal lock, accounts) loads in the background. */
final class CurrencyMenu extends Menu {

    private final String id;
    private CurrencyStats stats;

    CurrencyMenu(EditorGui gui, Player viewer, String id) {
        super(gui, viewer, 3, gui.msg().get("editor.title.currency", ph("id", id),
                ph("currency", gui.currency(id).map(CurrencyDefinition::displayName).orElse(id))));
        this.id = id;
        gui.service().stats(id).thenAccept(s -> gui.runLater(() -> {
            stats = s;
            if (isOpen()) {
                redraw();
            }
        }));
    }

    @Override
    protected void render() {
        Optional<CurrencyDefinition> found = gui.currency(id);
        if (found.isEmpty()) {
            return;
        }
        CurrencyDefinition c = found.get();
        TagResolver[] ph = gui.placeholders(c);
        boolean conflict = gui.conflicted(c.id());

        button(10, gui.item(Material.NAME_TAG, "display-name", ph), () -> gui.editText(viewer, id, Field.DISPLAY_NAME, c.displayName()));
        button(11, gui.item(Material.GOLD_NUGGET, "symbol", ph), () -> gui.editText(viewer, id, Field.SYMBOL, c.symbol()));
        button(12, gui.item(Material.PAPER, "format", ph), () -> gui.editText(viewer, id, Field.FORMAT, c.formatTemplate()));

        String decimalsKey = stats == null ? "decimals-checking"
                : conflict ? "decimals-conflict"
                : stats.registered() ? "decimals-locked" : "decimals-open";
        TagResolver stored = ph("stored", stats == null || stats.storedScale() == null ? "-" : stats.storedScale());
        button(13, gui.item(Material.REPEATER, decimalsKey, EditorGui.concat(ph, stored)), () -> gui.openDecimals(viewer, c, stats));

        button(14, gui.item(Material.CHEST, "starting", ph), () -> gui.editText(viewer, id, Field.STARTING, c.startingBalance().toPlainString()));
        String max = c.maxMinor() == Long.MAX_VALUE ? "none" : c.maxBalance().toPlainString();
        button(15, gui.item(Material.HOPPER, "max", ph), () -> gui.editText(viewer, id, Field.MAX, max));
        button(16, gui.item(Material.ENDER_PEARL, "transfer", ph), () -> gui.toggleTransfer(viewer, c));

        button(18, gui.item(Material.ARROW, "back"), () -> gui.openMain(viewer));
        button(22, gui.item(c.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "enabled", ph), () -> gui.toggleEnabled(viewer, c, stats));
        if (viewer.hasPermission(CurrencyEditor.PERM_DELETE)) {
            TagResolver accounts = ph("accounts", stats == null ? "?" : stats.accounts());
            button(26, gui.item(Material.TNT, "delete", EditorGui.concat(ph, accounts)), () -> gui.confirmDelete(viewer, c));
        }
    }
}
