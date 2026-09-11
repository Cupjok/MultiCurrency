package me.cupjok.multicurrency.bukkit;

import me.cupjok.multicurrency.core.service.CurrencyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Records player names asynchronously for offline lookups and leaderboards. */
final class PlayerNameListener implements Listener {

    private final CurrencyService service;

    PlayerNameListener(CurrencyService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.recordPlayerName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
