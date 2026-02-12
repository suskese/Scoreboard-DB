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
    private ScoreboardExpansion placeholderExpansion;

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
        String serverName = getServerName();
        databaseManager.populateScoreboardSettings(serverName);
        ScoreboardDBCommand commandExecutor = new ScoreboardDBCommand(this, databaseManager);
        getCommand("scoreboarddb").setExecutor(commandExecutor);
        getCommand("scoreboarddb").setTabCompleter(commandExecutor);
        
        // Register PlaceholderAPI expansion if enabled
        if (configLoader.isPlaceholdersEnabled()) {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                placeholderExpansion = new ScoreboardExpansion(this, configLoader, databaseManager);
                placeholderExpansion.register();
                getLogger().info("PlaceholderAPI expansion registered!");
            } else {
                getLogger().warning("PlaceholderAPI is not installed! Placeholder support disabled.");
            }
        }
        
        // Velocity plugin messaging
        Map<String, Object> velocity = configLoader.getVelocity();
        velocityEnabled = velocity != null && Boolean.TRUE.equals(velocity.getOrDefault("enabled", false));
        if (velocityEnabled) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "velocity:server");
            getServer().getMessenger().registerIncomingPluginChannel(this, "velocity:server", this);
            requestVelocityServerName();
        }

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
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
        boolean onlineOnly = configLoader.isSyncOnlineOnly();
        String upsertSql = databaseManager.getScoreboardDataUpsertSql();
        try (Connection conn = databaseManager.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                int count = 0;
                for (Objective obj : scoreboard.getObjectives()) {
                    String scoreboardName = obj.getName();

                    // Check per-scoreboard mode
                    try {
                        String mode = databaseManager.getScoreboardMode(serverName, scoreboardName);
                        if (mode.equals("pull")) {
                            // This scoreboard is pull-only, skip push
                            continue;
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to check mode for " + scoreboardName + ": " + e.getMessage());
                    }

                    Set<String> entries = scoreboard.getEntries();
                    for (String entry : entries) {
                        if (onlineOnly) {
                            Player player = Bukkit.getPlayer(entry);
                            if (player == null || !player.isOnline()) {
                                continue;
                            }
                        }
                        try {
                            Score score = obj.getScore(entry);
                            if (!score.isScoreSet()) continue;
                            double value = score.getScore();
                            ps.setString(1, serverName);
                            ps.setString(2, scoreboardName);
                            ps.setString(3, entry);
                            ps.setDouble(4, value);
                            ps.setBoolean(5, true);
                            ps.addBatch();
                            count++;
                        } catch (IllegalStateException ignore) {
                            // Entry does not have a score for this objective, skip
                        }
                    }
                }
                if (count > 0) {
                    ps.executeBatch();
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("scoreboard_data")) {
                databaseManager.ensureTableExists();
                getLogger().warning("Table was missing and has been created. Please try sync again.");
            } else {
                getLogger().warning("Failed to push scoreboard entry: " + e.getMessage());
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
                        
                        // Check per-scoreboard mode
                        try {
                            String mode = databaseManager.getScoreboardMode(serverName, scoreboardName);
                            if (mode.equals("push")) {
                                // This scoreboard is push-only, skip pull
                                continue;
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to check mode for " + scoreboardName + ": " + e.getMessage());
                        }
                        
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

    public void refreshPlaceholders() {
        if (placeholderExpansion != null) {
            placeholderExpansion.refreshPlaceholders();
        }
    }

    public ConfigLoader getConfigLoader() {
        return configLoader;
    }
}
