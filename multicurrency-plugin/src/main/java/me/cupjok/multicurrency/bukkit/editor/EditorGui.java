package me.cupjok.multicurrency.bukkit.editor;

import me.cupjok.multicurrency.api.CurrencyException;
import me.cupjok.multicurrency.bukkit.Messages;
import me.cupjok.multicurrency.bukkit.MultiCurrencyPlugin;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.Field;
import me.cupjok.multicurrency.bukkit.editor.CurrencyYaml.InvalidInput;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.service.CurrencyService;
import me.cupjok.multicurrency.core.service.CurrencyService.CurrencyStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/**
 * In-game currency editor: menus, chat input and the flows between them.
 *
 * <p>Every change goes through {@link CurrencyEditor#apply}, which re-checks the permission, validates
 * the whole configuration and reloads. Changes to economy settings and deletions need an extra
 * confirmation screen.
 */
public final class EditorGui implements Listener {

    private static final long PROMPT_MILLIS = 60_000;
    /** Shown in the format preview; display only, cut to the currency's decimals. */
    private static final BigDecimal EXAMPLE_AMOUNT = new BigDecimal("1234.56789");

    private record Prompt(Consumer<String> onInput, Runnable onCancel, long expiresAt) {
    }

    private final MultiCurrencyPlugin plugin;
    private final CurrencyEditor editor;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public EditorGui(MultiCurrencyPlugin plugin, CurrencyEditor editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    Messages msg() {
        return plugin.messages();
    }

    CurrencyService service() {
        return plugin.service();
    }

    Optional<CurrencyDefinition> currency(String id) {
        return service().registry().find(id);
    }

    boolean conflicted(String id) {
        return service().conflictedCurrencies().contains(id);
    }

    void runLater(Runnable task) {
        plugin.runSync(task);
    }

    // ================================================================== entry points

    public void openMain(Player player) {
        if (!player.hasPermission(CurrencyEditor.PERM_EDITOR)) {
            msg().send(player, "no-permission");
            return;
        }
        new MainMenu(this, player, 0).open();
    }

    void openCurrency(Player player, String id) {
        if (!player.isOnline()) {
            return;
        }
        if (currency(id).isEmpty()) {
            msg().send(player, "editor.missing", ph("id", id));
            new MainMenu(this, player, 0).open();
            return;
        }
        new CurrencyMenu(this, player, id).open();
    }

    /** Closes every open editor menu (plugin shutdown). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                p.closeInventory();
            }
        }
        prompts.clear();
    }

    // ================================================================== flows

    void reload(Player player) {
        if (!player.hasPermission("multicurrency.admin.reload")) {
            msg().send(player, "no-permission");
            return;
        }
        plugin.reloadCurrencies().whenComplete((registry, err) -> plugin.runSync(() -> {
            plugin.sendReloadResult(player, registry, err);
            if (player.isOnline()) {
                new MainMenu(this, player, 0).open();
            }
        }));
    }

    /** Display name, symbol, format: chat input, applied directly after validation. */
    void editText(Player player, String id, Field field, String current) {
        if (!player.hasPermission(field.economy() ? CurrencyEditor.PERM_ECONOMY : CurrencyEditor.PERM_EDITOR)) {
            msg().send(player, "no-permission");
            return;
        }
        prompt(player, field.key(), new TagResolver[]{ph("id", id), ph("current", current)}, input -> {
            String value;
            try {
                value = CurrencyYaml.parseText(field, input);
            } catch (InvalidInput e) {
                invalid(player, e);
                openCurrency(player, id);
                return;
            }
            if (field.economy()) {
                confirmAmount(player, id, field, current, value);
                return;
            }
            String change = "set " + id + "." + field.key() + ": '" + current + "' -> '" + value + "'";
            apply(player, CurrencyEditor.PERM_EDITOR, change, y -> CurrencyYaml.set(y, id, field, value), id);
        }, () -> openCurrency(player, id));
    }

    /** Starting and maximum balance: chat input, then a confirmation screen. */
    private void confirmAmount(Player player, String id, Field field, String current, String value) {
        String shown = value == null ? plainText("editor.unlimited") : value;
        String key = field == Field.STARTING ? "starting" : "max";
        new ConfirmMenu(this, player, msg().get("editor.title.confirm"), false,
                msg().lines("editor.confirm." + key, ph("id", id), ph("old", current), ph("new", shown)),
                () -> apply(player, CurrencyEditor.PERM_ECONOMY, "set " + id + "." + field.key() + ": " + current + " -> " + shown,
                        y -> CurrencyYaml.set(y, id, field, value), id),
                () -> openCurrency(player, id)).open();
    }

    void toggleTransfer(Player player, CurrencyDefinition c) {
        if (!player.hasPermission(CurrencyEditor.PERM_ECONOMY)) {
            msg().send(player, "no-permission");
            return;
        }
        boolean value = !c.transferEnabled();
        new ConfirmMenu(this, player, msg().get("editor.title.confirm"), false,
                msg().lines(value ? "editor.confirm.transfer-on" : "editor.confirm.transfer-off", ph("id", c.id()), ph("currency", c.displayName())),
                () -> apply(player, CurrencyEditor.PERM_ECONOMY, "set " + c.id() + ".transfer-enabled: " + !value + " -> " + value,
                        y -> CurrencyYaml.set(y, c.id(), Field.TRANSFER, value), c.id()),
                () -> openCurrency(player, c.id())).open();
    }

    /** @param stats may be null while still loading; enabling then warns about the decimal lock to be safe */
    void toggleEnabled(Player player, CurrencyDefinition c, CurrencyStats stats) {
        if (!player.hasPermission(CurrencyEditor.PERM_ECONOMY)) {
            msg().send(player, "no-permission");
            return;
        }
        if (conflicted(c.id())) {
            msg().send(player, "editor.conflict", ph("id", c.id()));
            return;
        }
        boolean value = !c.enabled();
        String key = !value ? "editor.confirm.disable" : stats != null && stats.registered() ? "editor.confirm.enable" : "editor.confirm.enable-lock";
        new ConfirmMenu(this, player, msg().get("editor.title.confirm"), false,
                msg().lines(key, ph("id", c.id()), ph("currency", c.displayName()), ph("decimals", c.scale())),
                () -> apply(player, CurrencyEditor.PERM_ECONOMY, "set " + c.id() + ".enabled: " + !value + " -> " + value,
                        y -> CurrencyYaml.set(y, c.id(), Field.ENABLED, value), c.id()),
                () -> openCurrency(player, c.id())).open();
    }

    void openDecimals(Player player, CurrencyDefinition c, CurrencyStats stats) {
        if (!player.hasPermission(CurrencyEditor.PERM_ECONOMY)) {
            msg().send(player, "no-permission");
            return;
        }
        if (stats == null) {
            msg().send(player, "editor.loading");
            return;
        }
        if (stats.registered() && stats.storedScale() == c.scale()) {
            msg().send(player, "editor.decimals-locked", ph("id", c.id()), ph("decimals", c.scale()));
            return;
        }
        new DecimalsMenu(this, player, c.id(), stats, false, c.scale()).open();
    }

    void setDecimals(Player player, String id, int from, int to) {
        apply(player, CurrencyEditor.PERM_ECONOMY, "set " + id + ".decimals: " + from + " -> " + to,
                y -> CurrencyYaml.set(y, id, Field.DECIMALS, to), id);
    }

    void startCreate(Player player) {
        if (!player.hasPermission(CurrencyEditor.PERM_CREATE)) {
            msg().send(player, "no-permission");
            return;
        }
        prompt(player, "id", new TagResolver[0], input -> {
            String id;
            try {
                id = CurrencyYaml.parseId(input);
            } catch (InvalidInput e) {
                invalid(player, e);
                new MainMenu(this, player, 0).open();
                return;
            }
            if (currency(id).isPresent()) {
                msg().send(player, "editor.invalid.exists", ph("value", id));
                new MainMenu(this, player, 0).open();
                return;
            }
            withStats(player, id, stats -> {
                if (stats.registered()) {
                    msg().send(player, "editor.archived", ph("id", id), ph("accounts", stats.accounts()),
                            ph("supply", stats.totalSupply().toPlainString()), ph("decimals", stats.storedScale()));
                }
                new DecimalsMenu(this, player, id, stats, true, null).open();
            });
        }, () -> new MainMenu(this, player, 0).open());
    }

    void create(Player player, String id, int decimals) {
        apply(player, CurrencyEditor.PERM_CREATE, "create currency " + id + " (decimals " + decimals + ", disabled)",
                y -> CurrencyYaml.create(y, id, decimals), id, () -> msg().send(player, "editor.created", ph("id", id)));
    }

    void confirmDelete(Player player, CurrencyDefinition c) {
        if (!player.hasPermission(CurrencyEditor.PERM_DELETE)) {
            msg().send(player, "no-permission");
            return;
        }
        withStats(player, c.id(), stats -> new ConfirmMenu(this, player,
                msg().get("editor.title.delete", ph("id", c.id()), ph("currency", c.displayName())), true,
                msg().lines("editor.confirm.delete", ph("id", c.id()), ph("currency", c.displayName()), ph("accounts", stats.accounts()),
                        ph("supply", c.formatNumber(stats.totalSupply())), ph("ledger", stats.ledgerEntries())),
                () -> {
                    String change = "delete currency " + c.id() + " (accounts " + stats.accounts() + ", supply "
                            + stats.totalSupply().toPlainString() + "; data kept in the database)";
                    boolean written = editor.apply(player, CurrencyEditor.PERM_DELETE, change, y -> CurrencyYaml.delete(y, c.id()), () -> {
                        msg().send(player, "editor.deleted", ph("id", c.id()));
                        new MainMenu(this, player, 0).open();
                    });
                    if (!written) {
                        openCurrency(player, c.id());
                    }
                },
                () -> openCurrency(player, c.id())).open());
    }

    private void apply(Player player, String permission, String change, CurrencyEditor.Edit edit, String reopenId) {
        apply(player, permission, change, edit, reopenId, () -> {
        });
    }

    private void apply(Player player, String permission, String change, CurrencyEditor.Edit edit, String reopenId, Runnable before) {
        boolean written = editor.apply(player, permission, change, edit, () -> {
            before.run();
            openCurrency(player, reopenId);
        });
        if (!written && player.isOnline()) {
            if (currency(reopenId).isPresent()) {
                openCurrency(player, reopenId);
            } else {
                new MainMenu(this, player, 0).open();
            }
        }
    }

    /** Loads stored data of a currency id in the background, then continues on the main thread. */
    void withStats(Player player, String id, Consumer<CurrencyStats> then) {
        service().stats(id).whenComplete((stats, err) -> plugin.runSync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (err != null) {
                Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                msg().send(player, cause instanceof CurrencyException ? Messages.errorKey(((CurrencyException) cause).reason()) : "error.storage-error",
                        ph("currency", id), ph("player", ""));
                return;
            }
            then.accept(stats);
        }));
    }

    private void invalid(Player player, InvalidInput e) {
        msg().send(player, "editor.invalid." + e.reason(), ph("value", e.getMessage() == null ? "" : e.getMessage()));
    }

    // ================================================================== chat input

    private void prompt(Player player, String key, TagResolver[] placeholders, Consumer<String> onInput, Runnable onCancel) {
        player.closeInventory();
        msg().send(player, "editor.prompt." + key, placeholders);
        msg().send(player, "editor.prompt.hint", ph("seconds", PROMPT_MILLIS / 1000));
        prompts.put(player.getUniqueId(), new Prompt(onInput, onCancel, System.currentTimeMillis() + PROMPT_MILLIS));
    }

    /**
     * Captures the answer to a prompt. Listens to the legacy chat event at the lowest priority because
     * Paper fires it first: cancelling here keeps the answer out of public chat even for chat plugins
     * that still use the legacy event.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Prompt prompt = prompts.remove(id);
        if (prompt == null) {
            return;
        }
        if (System.currentTimeMillis() > prompt.expiresAt()) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage();
        Player player = event.getPlayer();
        plugin.runSync(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (input.strip().equalsIgnoreCase("cancel")) {
                msg().send(player, "editor.prompt.cancelled");
                prompt.onCancel().run();
                return;
            }
            prompt.onInput().accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }

    // ================================================================== inventory protection

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        // Items in editor menus are display only: nothing may be taken, placed, swapped or collected.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            return;
        }
        menu.click(event.getRawSlot());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ================================================================== items and text

    ItemStack item(Material material, String key, TagResolver... placeholders) {
        return item(material, msg().get("editor.items." + key + ".name", placeholders), msg().lines("editor.items." + key + ".lore", placeholders));
    }

    static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> {
            meta.displayName(noItalic(name));
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(noItalic(line));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    TagResolver flag(String name, boolean value) {
        return Placeholder.parsed(name, plugin.getConfig().getString(value ? "messages.yes" : "messages.no", value ? "yes" : "no"));
    }

    private String plainText(String key) {
        return plugin.getConfig().getString("messages." + key, key);
    }

    /** Placeholders describing a currency, for item names and lore. */
    TagResolver[] placeholders(CurrencyDefinition c) {
        String max = c.maxMinor() == Long.MAX_VALUE ? plainText("editor.unlimited") : c.formatNumber(c.maxBalance());
        return new TagResolver[]{
                ph("id", c.id()),
                ph("currency", c.displayName()),
                ph("symbol", c.symbol().isEmpty() ? plainText("editor.none") : c.symbol()),
                ph("format", c.formatTemplate()),
                ph("example", c.format(EXAMPLE_AMOUNT)),
                ph("decimals", c.scale()),
                ph("starting", c.formatNumber(c.startingBalance())),
                ph("max", max),
                flag("transfer", c.transferEnabled()),
                flag("enabled", c.enabled()),
                flag("conflict", conflicted(c.id()))
        };
    }

    static TagResolver[] concat(TagResolver[] a, TagResolver... b) {
        TagResolver[] out = new TagResolver[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
