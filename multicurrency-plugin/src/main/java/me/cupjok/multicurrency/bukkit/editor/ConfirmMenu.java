package me.cupjok.multicurrency.bukkit.editor;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Yes/no screen before an economy change or a deletion. The confirm button never sits where the button
 * that opened this screen was, and clicks right after opening are ignored, so a double click cannot
 * confirm by accident. Closing the screen cancels.
 */
final class ConfirmMenu extends Menu {

    private final boolean danger;
    private final List<Component> info;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private boolean done;

    ConfirmMenu(EditorGui gui, Player viewer, Component title, boolean danger, List<Component> info, Runnable onConfirm, Runnable onCancel) {
        super(gui, viewer, 3, title);
        this.danger = danger;
        this.info = info;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /** Cancel must always work at once; only the confirm button waits (see {@link #guardMillis()}). */
    @Override
    protected long armDelayMillis() {
        return 0;
    }

    @Override
    protected long guardMillis() {
        return danger ? 1_000 : 400;
    }

    @Override
    protected void render() {
        guardedButton(11,gui.item(danger ? Material.RED_CONCRETE : Material.LIME_CONCRETE, danger ? "delete-confirm" : "confirm"), () -> {
            if (done) {
                return;
            }
            done = true;
            viewer.closeInventory();
            onConfirm.run();
        });
        button(13, EditorGui.item(Material.PAPER, gui.msg().get("editor.items.info.name"), info), null);
        button(15, gui.item(danger ? Material.LIME_CONCRETE : Material.RED_CONCRETE, "cancel"), () -> {
            if (done) {
                return;
            }
            done = true;
            onCancel.run();
        });
    }
}
