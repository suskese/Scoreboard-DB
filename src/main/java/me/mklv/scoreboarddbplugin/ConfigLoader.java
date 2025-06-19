package me.mklv.scoreboarddbplugin;

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

    @SuppressWarnings("unchecked")
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
}
