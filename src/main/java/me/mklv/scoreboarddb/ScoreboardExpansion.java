package me.mklv.scoreboarddb;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardExpansion extends PlaceholderExpansion {
    private final ScoreboardDBPlugin plugin;
    private final ConfigLoader configLoader;
    private final DatabaseManager databaseManager;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 30000; // Default 30 seconds, configurable

    public ScoreboardExpansion(ScoreboardDBPlugin plugin, ConfigLoader configLoader, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.databaseManager = databaseManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "sdb";
    }

    @Override
    public @NotNull String getAuthor() {
        return "IceVallish / Suskese";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of(
                "%sdb_<objective>%",
                "%sdb_<objective>_<entry>%",
                "%sdb_<objective>_{@}%",
                "%sdb_db_<objective>_<entry>%",
                "%sdb_db_<objective>_{@}%"
        );
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (!configLoader.isPlaceholdersEnabled()) {
            return null;
        }

        if (player == null) {
            return null;
        }

        // Clear cache if needed
        long currentTime = System.currentTimeMillis();
        int refreshInterval = configLoader.getPlaceholderRefreshInterval();
        long cacheMs = refreshInterval > 0 ? refreshInterval * 1000L : CACHE_DURATION_MS;

        cache.entrySet().removeIf(entry -> currentTime - entry.getValue().timestamp > cacheMs);

        if (params.startsWith("db_")) {
            return handleDatabasePlaceholder(player, params.substring(3));
        } else if (params.contains("_")) {
            return handleLocalPlaceholder(player, params);
        } else {
            // Just scoreboard name
            return handleScoreboardNamePlaceholder(params);
        }
    }

    @Nullable
    private String handleScoreboardNamePlaceholder(String scoreboardName) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return null;
        }

        Scoreboard scoreboard = manager.getMainScoreboard();
        Objective objective = scoreboard.getObjective(scoreboardName);
        if (objective == null) {
            return null;
        }

        Component displayName = objective.displayName();
        if (displayName == null) {
            return scoreboardName;
        }
        String displayNameText = PlainTextComponentSerializer.plainText().serialize(displayName);
        return displayNameText.isEmpty() ? scoreboardName : displayNameText;
    }

    @Nullable
    private String handleLocalPlaceholder(Player player, String params) {
        // Format: {scoreboard_name}_{player_name}
        // Extract parts
        int lastUnderscore = params.lastIndexOf('_');
        if (lastUnderscore <= 0) {
            return null;
        }

        String scoreboardName = params.substring(0, lastUnderscore);
        String entryName = params.substring(lastUnderscore + 1);

        // Replace {@} with player name
        if (entryName.equals("{@}")) {
            entryName = player.getName();
        }

        // Get from local scoreboard
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return null;
        }

        Scoreboard scoreboard = manager.getMainScoreboard();
        Objective objective = scoreboard.getObjective(scoreboardName);
        if (objective == null) {
            return null;
        }

        try {
            Score score = objective.getScore(entryName);
            if (score.isScoreSet()) {
                return String.valueOf(score.getScore());
            }
        } catch (IllegalStateException e) {
            return null;
        }

        return null;
    }

    @Nullable
    private String handleDatabasePlaceholder(Player player, String params) {
        // Format: {scoreboard_name}_{player_name}
        int lastUnderscore = params.lastIndexOf('_');
        if (lastUnderscore <= 0) {
            return null;
        }

        String scoreboardName = params.substring(0, lastUnderscore);
        String entryName = params.substring(lastUnderscore + 1);

        // Replace {@} with player name
        if (entryName.equals("{@}")) {
            entryName = player.getName();
        }

        // Check cache first
        String cacheKey = "db:" + scoreboardName + ":" + entryName;
        if (cache.containsKey(cacheKey)) {
            CachedValue cached = cache.get(cacheKey);
            if (System.currentTimeMillis() - cached.timestamp < getRefreshIntervalMs()) {
                return cached.value;
            }
        }

        // Query database
        String serverName = plugin.getServerName();
        try (Connection conn = databaseManager.getDataSource().getConnection()) {
            String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                ps.setString(3, entryName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        double value = rs.getDouble("value");
                        String result = String.valueOf((int) value);
                        int refreshInterval = configLoader.getPlaceholderRefreshInterval();
                        if (refreshInterval > 0) {
                            cache.put(cacheKey, new CachedValue(result, System.currentTimeMillis()));
                        }
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to query placeholder: " + e.getMessage());
        }

        return null;
    }

    private long getRefreshIntervalMs() {
        int interval = configLoader.getPlaceholderRefreshInterval();
        return interval > 0 ? interval * 1000L : CACHE_DURATION_MS;
    }

    public void refreshPlaceholders() {
        cache.clear();
    }

    private static class CachedValue {
        final String value;
        final long timestamp;

        CachedValue(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}
