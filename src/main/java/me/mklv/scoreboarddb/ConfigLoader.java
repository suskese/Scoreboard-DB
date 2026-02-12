package me.mklv.scoreboarddb;

import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

public class ConfigLoader {
    private final ScoreboardDBPlugin plugin;
    private Map<String, Object> config;

    public ConfigLoader(ScoreboardDBPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                plugin.saveResource("config.yml", false);
            }
            Yaml yaml = new Yaml();
            try (InputStream in = new FileInputStream(configFile)) {
                config = yaml.load(in);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load config: " + e.getMessage());
        }
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public boolean isUseLocal() {
        return Boolean.TRUE.equals(config.getOrDefault("use-local", false));
    }

    public int getSyncInterval() {
        Object val = config.get("sync-interval");
        return val instanceof Number ? ((Number) val).intValue() : 120;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLocal() {
        Object local = config.get("local");
        return local instanceof Map ? (Map<String, Object>) local : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getRemote() {
        Object remote = config.get("remote");
        return remote instanceof Map ? (Map<String, Object>) remote : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getVelocity() {
        Object velocity = config.get("velocity");
        return velocity instanceof Map ? (Map<String, Object>) velocity : null;
    }

    public String getServerName() {
        Map<String, Object> velocity = getVelocity();
        if (velocity != null && Boolean.TRUE.equals(velocity.getOrDefault("enabled", false))) {
            // TODO: Implement Velocity plugin messaging to get server name
            // For now, fallback to config value
            return velocity.getOrDefault("server-name", "default-server").toString();
        }
        if (velocity != null) {
            return velocity.getOrDefault("server-name", "default-server").toString();
        }
        return "default-server";
    }

    public String getSyncMode() {
        Object mode = config.get("sync-mode");
        if (mode == null) return "Both"; // Default to Both
        String modeStr = mode.toString().toUpperCase();
        if (modeStr.equals("PUSH") || modeStr.equals("PULL") || modeStr.equals("BOTH")) {
            return modeStr;
        }
        return "Both"; // Default if invalid
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlaceholders() {
        Object placeholders = config.get("placeholders");
        return placeholders instanceof Map ? (Map<String, Object>) placeholders : null;
    }

    public boolean isPlaceholdersEnabled() {
        Map<String, Object> placeholders = getPlaceholders();
        return placeholders != null && Boolean.TRUE.equals(placeholders.getOrDefault("enabled", false));
    }

    public int getPlaceholderRefreshInterval() {
        Map<String, Object> placeholders = getPlaceholders();
        if (placeholders == null) return 30;
        Object val = placeholders.get("refresh-interval");
        return val instanceof Number ? ((Number) val).intValue() : 30;
    }

    public boolean isAutoSyncOnJoinEnabled() {
        return Boolean.TRUE.equals(config.getOrDefault("auto-sync-on-join", false));
    }

    public int getJoinSyncDebounceSeconds() {
        Object val = config.get("join-sync-debounce-seconds");
        return val instanceof Number ? ((Number) val).intValue() : 10;
    }

    public boolean isSyncOnlineOnly() {
        return Boolean.TRUE.equals(config.getOrDefault("sync-online-only", false));
    }
}
