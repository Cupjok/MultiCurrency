package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.service.CurrencyService.CurrencyStats;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/**
 * Chooses decimal places, for a new currency or one that was never enabled. Once balances of an id were
 * stored, only the stored scale can be chosen (that repairs a scale conflict; anything else is locked).
 */
final class DecimalsMenu extends Menu {

    private final String id;
    private final CurrencyStats stats;
    private final boolean create;
    private final Integer current;

    DecimalsMenu(EditorGui gui, Player viewer, String id, CurrencyStats stats, boolean create, Integer current) {
        super(gui, viewer, 3, gui.msg().get("editor.title.decimals", ph("id", id)));
        this.id = id;
        this.stats = stats;
        this.create = create;
        this.current = current;
    }

    @Override
    protected void render() {
        for (int d = 0; d <= CurrencyDefinition.MAX_SCALE; d++) {
            int value = d;
            boolean allowed = !stats.registered() || stats.storedScale() == d;
            String smallest = d == 0 ? "1" : "0." + "0".repeat(d - 1) + "1";
            String key = current != null && current == d ? "decimal-current" : allowed ? "decimal-option" : "decimal-locked";
            Material material = key.equals("decimal-current") ? Material.LIME_STAINED_GLASS_PANE
                    : allowed ? Material.WHITE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            ItemStack item = gui.item(material, key, ph("value", d), ph("smallest", smallest), ph("id", id));
            item.setAmount(Math.max(1, d));
            Runnable action = null;
            if (allowed && (current == null || current != d)) {
                action = () -> {
                    if (create) {
                        gui.create(viewer, id, value);
                    } else {
                        gui.setDecimals(viewer, id, current, value);
                    }
                };
            }
            button(9 + d, item, action);
        }
        button(22, gui.item(Material.ARROW, "back"), () -> {
            if (create) {
                gui.openMain(viewer);
            } else {
                gui.openCurrency(viewer, id);
            }
        });
    }
}
