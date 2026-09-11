package me.cupjok.multicurrency.bukkit.editor;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A chest menu of the editor. Items are display only: {@link EditorGui} cancels every click and drag
 * in these inventories and forwards clicks on the menu's own slots to the registered action.
 */
abstract class Menu implements InventoryHolder {

    protected final EditorGui gui;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private final Set<Integer> guarded = new HashSet<>();
    private long openedAt;

    Menu(EditorGui gui, Player viewer, int rows, Component title) {
        this.gui = gui;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected abstract void render();

    /**
     * Clicks this soon after opening are ignored, so a double click cannot confirm by accident. Applies
     * to every button, and for {@link #guardedButton} buttons a longer {@link #guardMillis()} applies.
     */
    protected long armDelayMillis() {
        return 250;
    }

    /** Minimum time after opening before a guarded button (confirm) reacts. Cancel buttons are never guarded. */
    protected long guardMillis() {
        return 0;
    }

    final void open() {
        redraw();
        viewer.openInventory(inventory);
        openedAt = System.currentTimeMillis();
    }

    /** Rebuilds the items, e.g. when data loaded in the background arrives. */
    final void redraw() {
        inventory.clear();
        actions.clear();
        guarded.clear();
        render();
    }

    final boolean isOpen() {
        return viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    protected final void button(int slot, ItemStack item, Runnable action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected final void guardedButton(int slot, ItemStack item, Runnable action) {
        button(slot, item, action);
        guarded.add(slot);
    }

    final void click(int slot) {
        long age = System.currentTimeMillis() - openedAt;
        if (age < armDelayMillis() || guarded.contains(slot) && age < guardMillis()) {
            return;
        }
        Runnable action = actions.get(slot);
        if (action != null) {
            action.run();
        }
    }
}
