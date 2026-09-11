package me.cupjok.multicurrency.bukkit.command;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.BalanceEntry;
import me.cupjok.multicurrency.api.CurrencyException;
import me.cupjok.multicurrency.api.FailureReason;
import me.cupjok.multicurrency.api.TransactionContext;
import me.cupjok.multicurrency.api.TransactionRecord;
import me.cupjok.multicurrency.api.TransactionResult;
import me.cupjok.multicurrency.bukkit.Messages;
import me.cupjok.multicurrency.bukkit.MultiCurrencyPlugin;
import me.cupjok.multicurrency.bukkit.editor.CurrencyEditor;
import me.cupjok.multicurrency.core.currency.CurrencyDefinition;
import me.cupjok.multicurrency.core.service.CurrencyService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static me.cupjok.multicurrency.bukkit.Messages.ph;

/**
 * {@code /currency}, {@code /cbal} and {@code /cpay}.
 *
 * <p>This class only parses input, checks command permissions, calls the service and prints the
 * result. Every currency rule (transfer-enabled, limits, precision, funds) is enforced by
 * {@link CurrencyService}; nothing here can grant more than the API would.
 */
public final class CurrencyCommand implements TabExecutor {

    public static final String PERM_BALANCE = "multicurrency.command.balance";
    public static final String PERM_BALANCE_OTHERS = "multicurrency.command.balance.others";
    public static final String PERM_PAY = "multicurrency.command.pay";
    public static final String PERM_LIST = "multicurrency.command.list";
    public static final String PERM_INFO = "multicurrency.command.info";
    public static final String PERM_TOP = "multicurrency.command.top";
    public static final String PERM_HISTORY = "multicurrency.command.history";
    public static final String PERM_HISTORY_OTHERS = "multicurrency.command.history.others";
    public static final String PERM_GIVE = "multicurrency.admin.give";
    public static final String PERM_TAKE = "multicurrency.admin.take";
    public static final String PERM_SET = "multicurrency.admin.set";
    public static final String PERM_RELOAD = "multicurrency.admin.reload";

    /** Plain decimal only: no signs, exponents, separators or NaN. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,20}(\\.\\d{1,18})?");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private record Target(UUID id, String name) {
    }

    private final MultiCurrencyPlugin plugin;

    public CurrencyCommand(MultiCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    private CurrencyService service() {
        return plugin.service();
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ================================================================== dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (service() == null) {
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "cbal" -> balance(sender, args);
            case "cpay" -> pay(sender, args);
            default -> {
                if (args.length == 0) {
                    help(sender);
                    return true;
                }
                String[] rest = Arrays.copyOfRange(args, 1, args.length);
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "balance", "bal" -> balance(sender, rest);
                    case "pay" -> pay(sender, rest);
                    case "list" -> list(sender);
                    case "info" -> info(sender, rest);
                    case "top" -> top(sender, rest);
                    case "history" -> history(sender, rest);
                    case "give", "add" -> admin(sender, rest, Op.GIVE);
                    case "take", "remove" -> admin(sender, rest, Op.TAKE);
                    case "set" -> admin(sender, rest, Op.SET);
                    case "reload" -> reload(sender);
                    case "editor", "gui", "edit" -> editor(sender);
                    default -> help(sender);
                }
            }
        }
        return true;
    }

    private void help(CommandSender sender) {
        msg().send(sender, "help");
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        msg().send(sender, "no-permission");
        return false;
    }

    // ================================================================== balance

    private void balance(CommandSender sender, String[] args) {
        if (!permitted(sender, PERM_BALANCE)) {
            return;
        }
        String playerArg = null;
        String currencyArg = null;
        if (args.length == 1) {
            if (service().registry().find(args[0]).isPresent()) {
                currencyArg = args[0];
            } else {
                playerArg = args[0];
            }
        } else if (args.length >= 2) {
            playerArg = args[0];
            currencyArg = args[1];
        }
        if (currencyArg != null && service().registry().find(currencyArg).isEmpty()) {
            msg().send(sender, Messages.errorKey(FailureReason.UNKNOWN_CURRENCY), ph("currency", currencyArg));
            return;
        }
        if (playerArg == null) {
            if (!(sender instanceof Player p)) {
                msg().send(sender, "usage.balance");
                return;
            }
            showBalances(sender, new Target(p.getUniqueId(), p.getName()), currencyArg);
            return;
        }
        if (!permitted(sender, PERM_BALANCE_OTHERS)) {
            return;
        }
        String currency = currencyArg;
        resolve(sender, playerArg, target -> showBalances(sender, target, currency));
    }

    private void showBalances(CommandSender sender, Target target, String currencyId) {
        List<CurrencyDefinition> currencies = new ArrayList<>();
        if (currencyId != null) {
            service().registry().find(currencyId).ifPresent(currencies::add);
        } else {
            for (CurrencyDefinition c : service().registry().all()) {
                if (c.enabled()) {
                    currencies.add(c);
                }
            }
        }
        if (currencies.isEmpty()) {
            msg().send(sender, "balance.none");
            return;
        }
        List<CompletableFuture<BigDecimal>> futures = new ArrayList<>();
        for (CurrencyDefinition c : currencies) {
            futures.add(service().balance(target.id(), c.id()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> plugin.runSync(() -> {
            if (err != null) {
                sendFailure(sender, err);
                return;
            }
            msg().send(sender, "balance.header", ph("player", target.name()));
            for (int i = 0; i < currencies.size(); i++) {
                CurrencyDefinition c = currencies.get(i);
                msg().send(sender, "balance.line", ph("currency", c.displayName()), ph("id", c.id()),
                        ph("balance", c.format(futures.get(i).join())));
            }
        }));
    }

    // ================================================================== pay

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player payer)) {
            msg().send(sender, "players-only");
            return;
        }
        if (!permitted(sender, PERM_PAY)) {
            return;
        }
        if (args.length != 3) {
            msg().send(sender, "usage.pay");
            return;
        }
        Optional<CurrencyDefinition> currency = service().registry().find(args[1]);
        if (currency.isEmpty()) {
            msg().send(sender, Messages.errorKey(FailureReason.UNKNOWN_CURRENCY), ph("currency", args[1]));
            return;
        }
        BigDecimal amount = parseAmount(sender, args[2]);
        if (amount == null) {
            return;
        }
        CurrencyDefinition c = currency.get();
        resolve(sender, args[0], target -> {
            TransactionContext ctx = TransactionContext.of(Actor.player(payer.getUniqueId(), payer.getName()), "pay command");
            service().transfer(payer.getUniqueId(), target.id(), c.id(), amount, ctx).whenComplete((result, err) -> plugin.runSync(() -> {
                if (err != null || !result.success()) {
                    sendFailure(sender, err, result, c, target);
                    return;
                }
                msg().send(sender, "pay.sent", ph("player", target.name()), ph("amount", c.format(amount)),
                        ph("currency", c.displayName()), ph("balance", c.format(result.balanceAfter())));
                Player recipient = Bukkit.getPlayer(target.id());
                if (recipient != null) {
                    msg().send(recipient, "pay.received", ph("player", payer.getName()), ph("amount", c.format(amount)),
                            ph("currency", c.displayName()), ph("balance", c.format(result.counterpartyBalanceAfter())));
                }
            }));
        });
    }

    // ================================================================== admin

    private enum Op { GIVE, TAKE, SET }

    private void admin(CommandSender sender, String[] args, Op op) {
        String permission = switch (op) {
            case GIVE -> PERM_GIVE;
            case TAKE -> PERM_TAKE;
            case SET -> PERM_SET;
        };
        if (!permitted(sender, permission)) {
            return;
        }
        if (args.length < 3) {
            msg().send(sender, "usage." + op.name().toLowerCase(Locale.ROOT));
            return;
        }
        Optional<CurrencyDefinition> currency = service().registry().find(args[1]);
        if (currency.isEmpty()) {
            msg().send(sender, Messages.errorKey(FailureReason.UNKNOWN_CURRENCY), ph("currency", args[1]));
            return;
        }
        BigDecimal amount = parseAmount(sender, args[2]);
        if (amount == null) {
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : op.name().toLowerCase(Locale.ROOT) + " command";
        Actor actor = sender instanceof Player p ? Actor.player(p.getUniqueId(), p.getName()) : Actor.console();
        TransactionContext ctx = TransactionContext.of(actor, reason);
        CurrencyDefinition c = currency.get();
        resolve(sender, args[0], target -> {
            CompletableFuture<TransactionResult> future = switch (op) {
                case GIVE -> service().deposit(target.id(), c.id(), amount, ctx);
                case TAKE -> service().withdraw(target.id(), c.id(), amount, ctx);
                case SET -> service().set(target.id(), c.id(), amount, ctx);
            };
            future.whenComplete((result, err) -> plugin.runSync(() -> {
                if (err != null || !result.success()) {
                    sendFailure(sender, err, result, c, target);
                    return;
                }
                msg().send(sender, "admin." + op.name().toLowerCase(Locale.ROOT), ph("player", target.name()),
                        ph("amount", c.format(amount)), ph("currency", c.displayName()),
                        ph("balance", c.format(result.balanceAfter())), ph("tx", result.transactionId()));
            }));
        });
    }

    private void reload(CommandSender sender) {
        if (!permitted(sender, PERM_RELOAD)) {
            return;
        }
        try {
            plugin.reloadCurrencies().whenComplete((registry, err) -> plugin.runSync(() -> plugin.sendReloadResult(sender, registry, err)));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Reload failed: " + e.getMessage());
            msg().send(sender, "reload.failed");
        }
    }

    private void editor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg().send(sender, "players-only");
            return;
        }
        if (!permitted(sender, CurrencyEditor.PERM_EDITOR)) {
            return;
        }
        plugin.editorGui().openMain(player);
    }

    // ================================================================== list / info / top / history

    private void list(CommandSender sender) {
        if (!permitted(sender, PERM_LIST)) {
            return;
        }
        msg().send(sender, "list.header", ph("count", service().registry().all().size()));
        for (CurrencyDefinition c : service().registry().all()) {
            msg().send(sender, c.enabled() ? "list.line" : "list.line-disabled", ph("id", c.id()), ph("currency", c.displayName()),
                    ph("symbol", c.symbol()), flag("transfer", c.transferEnabled()));
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (!permitted(sender, PERM_INFO)) {
            return;
        }
        if (args.length != 1) {
            msg().send(sender, "usage.info");
            return;
        }
        Optional<CurrencyDefinition> found = service().registry().find(args[0]);
        if (found.isEmpty()) {
            msg().send(sender, Messages.errorKey(FailureReason.UNKNOWN_CURRENCY), ph("currency", args[0]));
            return;
        }
        CurrencyDefinition c = found.get();
        msg().send(sender, "info", ph("id", c.id()), ph("currency", c.displayName()), ph("symbol", c.symbol()),
                flag("enabled", c.enabled()), flag("transfer", c.transferEnabled()), ph("decimals", c.scale()),
                ph("starting", c.format(c.startingBalance())), ph("max", c.format(c.maxBalance())),
                flag("conflict", service().conflictedCurrencies().contains(c.id())));
    }

    private void top(CommandSender sender, String[] args) {
        if (!permitted(sender, PERM_TOP)) {
            return;
        }
        if (args.length < 1) {
            msg().send(sender, "usage.top");
            return;
        }
        Optional<CurrencyDefinition> found = service().registry().find(args[0]);
        if (found.isEmpty()) {
            msg().send(sender, Messages.errorKey(FailureReason.UNKNOWN_CURRENCY), ph("currency", args[0]));
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Math.min(1000, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                msg().send(sender, "usage.top");
                return;
            }
        }
        CurrencyDefinition c = found.get();
        int offset = (page - 1) * PAGE_SIZE;
        int shownPage = page;
        service().top(c.id(), PAGE_SIZE, offset).whenComplete((rows, err) -> plugin.runSync(() -> {
            if (err != null) {
                sendFailure(sender, err);
                return;
            }
            msg().send(sender, "top.header", ph("currency", c.displayName()), ph("page", shownPage));
            int rank = offset;
            for (BalanceEntry row : rows) {
                rank++;
                msg().send(sender, "top.line", ph("rank", rank), ph("player", nameOf(row.player(), row.lastKnownName())),
                        ph("balance", c.format(row.balance())));
            }
            if (rows.isEmpty()) {
                msg().send(sender, "top.empty");
            }
        }));
    }

    private void history(CommandSender sender, String[] args) {
        if (!permitted(sender, PERM_HISTORY)) {
            return;
        }
        String playerArg = null;
        String currencyArg = null;
        if (args.length == 1) {
            if (service().registry().find(args[0]).isPresent()) {
                currencyArg = args[0];
            } else {
                playerArg = args[0];
            }
        } else if (args.length >= 2) {
            playerArg = args[0];
            currencyArg = args[1];
        }
        String currency = currencyArg;
        if (playerArg == null) {
            if (!(sender instanceof Player p)) {
                msg().send(sender, "usage.history");
                return;
            }
            showHistory(sender, new Target(p.getUniqueId(), p.getName()), currency);
            return;
        }
        if (!permitted(sender, PERM_HISTORY_OTHERS)) {
            return;
        }
        resolve(sender, playerArg, target -> showHistory(sender, target, currency));
    }

    private void showHistory(CommandSender sender, Target target, String currencyId) {
        service().history(target.id(), currencyId, PAGE_SIZE).whenComplete((rows, err) -> plugin.runSync(() -> {
            if (err != null) {
                sendFailure(sender, err);
                return;
            }
            msg().send(sender, "history.header", ph("player", target.name()));
            if (rows.isEmpty()) {
                msg().send(sender, "history.empty");
            }
            for (TransactionRecord r : rows) {
                Optional<CurrencyDefinition> c = service().registry().find(r.currencyId());
                String amount = c.map(d -> d.format(r.amount())).orElse(r.amount().toPlainString() + " " + r.currencyId());
                String direction = "";
                if (r.counterparty() != null) {
                    direction = r.account().equals(target.id())
                            ? "→ " + nameOf(r.counterparty(), null)
                            : "← " + nameOf(r.account(), null);
                }
                msg().send(sender, "history.line", ph("time", TIME.format(r.timestamp())), ph("type", r.type().name()),
                        ph("amount", amount), ph("direction", direction), ph("actor", r.actor().name()),
                        ph("reason", r.reason() == null ? "" : r.reason()), ph("tx", r.transactionId()));
            }
        }));
    }

    // ================================================================== helpers

    private BigDecimal parseAmount(CommandSender sender, String raw) {
        if (!AMOUNT.matcher(raw).matches()) {
            msg().send(sender, Messages.errorKey(FailureReason.INVALID_AMOUNT));
            return null;
        }
        return new BigDecimal(raw);
    }

    /** Resolves online, then server-cached, then MultiCurrency-recorded names. Unknown names are refused. */
    private void resolve(CommandSender sender, String name, Consumer<Target> then) {
        if (!NAME.matcher(name).matches()) {
            msg().send(sender, "unknown-player", ph("player", name));
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(new Target(online.getUniqueId(), online.getName()));
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            then.accept(new Target(cached.getUniqueId(), cached.getName()));
            return;
        }
        service().findPlayerByName(name).whenComplete((found, err) -> plugin.runSync(() -> {
            if (err != null || found.isEmpty()) {
                msg().send(sender, "unknown-player", ph("player", name));
            } else {
                then.accept(new Target(found.get(), name));
            }
        }));
    }

    private static String nameOf(UUID id, String known) {
        if (known != null) {
            return known;
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    /** yes/no text from config.yml (admin-authored MiniMessage, so parsed). */
    private TagResolver flag(String name, boolean value) {
        return Placeholder.parsed(name, plugin.getConfig().getString(value ? "messages.yes" : "messages.no", value ? "yes" : "no"));
    }

    private void sendFailure(CommandSender sender, Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        FailureReason reason = cause instanceof CurrencyException ce ? ce.reason() : FailureReason.STORAGE_ERROR;
        msg().send(sender, Messages.errorKey(reason), ph("currency", ""), ph("player", ""));
    }

    private void sendFailure(CommandSender sender, Throwable err, TransactionResult result, CurrencyDefinition c, Target target) {
        if (err != null || result == null) {
            sendFailure(sender, err);
            return;
        }
        msg().send(sender, Messages.errorKey(result.failureReason()), ph("currency", c.displayName()), ph("player", target.name()),
                ph("max", c.format(c.maxBalance())), ph("decimals", c.scale()));
    }

    // ================================================================== tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (service() == null) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("cbal")) {
            return complete(args.length == 1 ? union(currencies(), players()) : args.length == 2 ? currencies() : List.of(), args);
        }
        if (name.equals("cpay")) {
            return completePay(args, 0);
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            addIf(sender, subs, PERM_BALANCE, "balance");
            addIf(sender, subs, PERM_PAY, "pay");
            addIf(sender, subs, PERM_LIST, "list");
            addIf(sender, subs, PERM_INFO, "info");
            addIf(sender, subs, PERM_TOP, "top");
            addIf(sender, subs, PERM_HISTORY, "history");
            addIf(sender, subs, PERM_GIVE, "give");
            addIf(sender, subs, PERM_TAKE, "take");
            addIf(sender, subs, PERM_SET, "set");
            addIf(sender, subs, PERM_RELOAD, "reload");
            addIf(sender, subs, CurrencyEditor.PERM_EDITOR, "editor");
            return complete(subs, args);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        int pos = args.length - 1;
        return switch (sub) {
            case "pay" -> completePay(args, 1);
            case "balance", "bal", "history" -> complete(pos == 1 ? union(currencies(), players()) : pos == 2 ? currencies() : List.of(), args);
            case "info", "top" -> complete(pos == 1 ? currencies() : List.of(), args);
            case "give", "add", "take", "remove", "set" -> complete(pos == 1 ? players() : pos == 2 ? currencies() : List.of(), args);
            default -> List.of();
        };
    }

    private List<String> completePay(String[] args, int offset) {
        int pos = args.length - 1 - offset;
        if (pos == 0) {
            return complete(players(), args);
        }
        if (pos == 1) {
            List<String> transferable = new ArrayList<>();
            for (CurrencyDefinition c : service().registry().all()) {
                if (c.enabled() && c.transferEnabled()) {
                    transferable.add(c.id());
                }
            }
            return complete(transferable, args);
        }
        return List.of();
    }

    private static void addIf(CommandSender sender, List<String> out, String permission, String value) {
        if (sender.hasPermission(permission)) {
            out.add(value);
        }
    }

    private List<String> currencies() {
        List<String> out = new ArrayList<>();
        for (CurrencyDefinition c : service().registry().all()) {
            out.add(c.id());
        }
        return out;
    }

    private static List<String> players() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
        }
        return out;
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> complete(List<String> options, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(o);
            }
        }
        return out;
    }
}
