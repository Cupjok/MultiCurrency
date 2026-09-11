package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/** Currency overview: one item per currency, plus create, reload and paging. */
final class MainMenu extends Menu {

    private static final int PER_PAGE = 45;

    private final int page;

    MainMenu(EditorGui gui, Player viewer, int page) {
        super(gui, viewer, 6, gui.msg().get("editor.title.main", ph("page", page + 1)));
        this.page = Math.max(0, page);
    }

    @Override
    protected void render() {
        List<CurrencyDefinition> all = List.copyOf(gui.service().registry().all());
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < all.size(); i++) {
            CurrencyDefinition c = all.get(from + i);
            Material material = gui.conflicted(c.id()) ? Material.BARRIER : c.enabled() ? Material.GOLD_INGOT : Material.IRON_INGOT;
            button(i, gui.item(material, "currency", gui.placeholders(c)), () -> gui.openCurrency(viewer, c.id()));
        }
        if (page > 0) {
            button(45, gui.item(Material.ARROW, "previous"), () -> new MainMenu(gui, viewer, page - 1).open());
        }
        if (from + PER_PAGE < all.size()) {
            button(53, gui.item(Material.ARROW, "next"), () -> new MainMenu(gui, viewer, page + 1).open());
        }
        if (viewer.hasPermission("multicurrency.admin.reload")) {
            button(47, gui.item(Material.CLOCK, "reload"), () -> gui.reload(viewer));
        }
        if (viewer.hasPermission(CurrencyEditor.PERM_CREATE)) {
            button(49, gui.item(Material.EMERALD, "create"), () -> gui.startCreate(viewer));
        }
        button(51, gui.item(Material.OAK_DOOR, "close"), viewer::closeInventory);
    }
}
