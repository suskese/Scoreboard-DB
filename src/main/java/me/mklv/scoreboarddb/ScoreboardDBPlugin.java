package me.mklv.scoreboarddb;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.ScoreboardManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class ScoreboardDBPlugin extends JavaPlugin implements PluginMessageListener {
    private ConfigLoader configLoader;
    private DatabaseManager databaseManager;
    private static ScoreboardDBPlugin instance;
    private BukkitRunnable syncTask;
    private int syncTaskId = -1;

    private final AtomicReference<String> velocityServerName = new AtomicReference<>(null);
    private boolean velocityEnabled = false;

    public static ScoreboardDBPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        // Load config, initialize DB, register commands, schedule sync
        saveDefaultConfig();
        configLoader = new ConfigLoader(this);
        databaseManager = new DatabaseManager(this, configLoader);
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            getLogger().severe("PostgreSQL JDBC Driver not found! Ignore this error if use-local: true");
        }
        databaseManager.init();
        getCommand("scoreboarddb").setExecutor(new ScoreboardDBCommand(this, databaseManager));
        // Velocity plugin messaging
        Map<String, Object> velocity = configLoader.getVelocity();
        velocityEnabled = velocity != null && Boolean.TRUE.equals(velocity.getOrDefault("enabled", false));
        if (velocityEnabled) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "velocity:server");
            getServer().getMessenger().registerIncomingPluginChannel(this, "velocity:server", this);
            requestVelocityServerName();
        }
        startSyncTask();
        getLogger().info("Plugin enabled!");
    }

    private void requestVelocityServerName() {
        // Send a plugin message to request the server name from Velocity
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (player != null) {
                player.sendPluginMessage(this, "velocity:server", new byte[0]);
            }
        }, 40L); // Wait 2 seconds after startup
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("velocity:server")) return;
        String name = new String(message);
        velocityServerName.set(name);
        getLogger().info("Velocity server name received: " + name);
    }

    @Override
    public void onDisable() {
        // Cleanup resources
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (syncTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
        getLogger().info("Plugin disabled!");
    }

    public void startSyncTask() {
        int interval = configLoader.getSyncInterval();
        if (syncTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
        // If sync-interval is 0, disable automatic sync
        if (interval <= 0) {
            getLogger().info("Automatic sync disabled (sync-interval: 0). Use /scoreboarddb sync-now for manual sync.");
            return;
        }
        
        // Check if Folia is available
        if (isFoliaAvailable()) {
            // Use Folia-compatible globalRegionScheduler
            try {
                Object globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                java.lang.reflect.Method runAtFixedRate = globalScheduler.getClass()
                    .getMethod("runAtFixedRate", 
                        org.bukkit.plugin.Plugin.class,
                        java.util.function.Consumer.class,
                        long.class,
                        long.class);
                runAtFixedRate.invoke(globalScheduler, this, (java.util.function.Consumer<Object>) task -> {
                    syncDatabase();
                }, interval * 20L, interval * 20L);
                getLogger().info("Using Folia globalRegionScheduler for sync task");
            } catch (Exception e) {
                getLogger().severe("Failed to use Folia scheduler: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Use async scheduler for Paper
            syncTask = new BukkitRunnable() {
                @Override
                public void run() {
                    syncDatabase();
                }
            };
            syncTaskId = syncTask.runTaskTimerAsynchronously(this, interval * 20L, interval * 20L).getTaskId();
        }
    }

    private boolean isFoliaAvailable() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public void syncDatabase() {
        getLogger().info("Starting scoreboard sync...");
        
        if (isFoliaAvailable()) {
            // On Folia, run directly since globalRegionScheduler is already async-safe
            performSync();
        } else {
            // On Paper, use async scheduler
            Bukkit.getScheduler().runTaskAsynchronously(this, this::performSync);
        }
    }

    private void performSync() {
        try {
            String mode = configLoader.getSyncMode();
            if (!mode.equals("PUSH")) {
                getLogger().info("Pulling scoreboard from DB...");
                pullScoreboardFromDB();
            }
            if (!mode.equals("PULL")) {
                getLogger().info("Pushing scoreboard to DB...");
                pushScoreboardToDB();
            }
            getLogger().info("Sync complete");
        } catch (Exception e) {
            getLogger().severe("Sync failed: " + e.getMessage());
        }
    }

    private void pushScoreboardToDB() {
        String serverName = getServerName();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard scoreboard = manager.getMainScoreboard();
        for (Objective obj : scoreboard.getObjectives()) {
            String scoreboardName = obj.getName();
            Set<String> entries = scoreboard.getEntries();
            for (String entry : entries) {
                // Only sync if this objective has a score for this entry
                try {
                    Score score = obj.getScore(entry);
                    // Only push if the score is set for this objective/entry (avoid default 0s for all entries)
                    if (!score.isScoreSet()) continue;
                    double value = score.getScore();
                    try (Connection conn = databaseManager.getDataSource().getConnection()) {
                        String sql = "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) VALUES (?, ?, ?, ?, ?) " +
                                "ON CONFLICT(server_name, scoreboard_name, string) DO UPDATE SET value = excluded.value, push = excluded.push";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, serverName);
                            ps.setString(2, scoreboardName);
                            ps.setString(3, entry);
                            ps.setDouble(4, value);
                            ps.setBoolean(5, false); // When pushing from Minecraft, set push to false
                            ps.executeUpdate();
                        }
                    }
                } catch (IllegalStateException ignore) {
                    // This entry does not have a score for this objective, skip
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("relation \"scoreboard_data\" does not exist")) {
                        databaseManager.ensureTableExists();
                        getLogger().warning("Table was missing and has been created. Please try sync again.");
                    } else {
                        getLogger().warning("Failed to push scoreboard entry: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void pullScoreboardFromDB() {
        String serverName = getServerName();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard scoreboard = manager.getMainScoreboard();
        try (Connection conn = databaseManager.getDataSource().getConnection()) {
            String sql = "SELECT scoreboard_name, string, value, push FROM scoreboard_data WHERE server_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String scoreboardName = rs.getString("scoreboard_name");
                        String entry = rs.getString("string");
                        double value = rs.getDouble("value");
                        boolean push = rs.getBoolean("push");
                        Objective obj = scoreboard.getObjective(scoreboardName);
                        if (obj != null && push) {
                            obj.getScore(entry).setScore((int) value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to pull scoreboard data: " + e.getMessage());
        }
    }

    public String getServerName() {
        if (velocityEnabled && velocityServerName.get() != null) {
            return velocityServerName.get();
        }
        return configLoader.getServerName();
    }
}
