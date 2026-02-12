package me.mklv.scoreboarddb;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.atomic.AtomicLong;

public class JoinListener implements Listener {
    private final ScoreboardDBPlugin plugin;
    private final AtomicLong lastSyncMs = new AtomicLong(0L);

    public JoinListener(ScoreboardDBPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigLoader().isAutoSyncOnJoinEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long debounceMs = plugin.getConfigLoader().getJoinSyncDebounceSeconds() * 1000L;
        long last = lastSyncMs.get();
        if (debounceMs > 0 && (now - last) < debounceMs) {
            return;
        }
        if (lastSyncMs.compareAndSet(last, now)) {
            plugin.syncDatabase();
        }
    }
}
