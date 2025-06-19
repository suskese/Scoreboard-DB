package me.mklv.scoreboarddbplugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public class DatabaseManager {
    private final Plugin plugin;
    private final ConfigLoader configLoader;
    private HikariDataSource dataSource;

    public DatabaseManager(Plugin plugin, ConfigLoader configLoader) {
        this.plugin = plugin;
        this.configLoader = configLoader;
    }

    public void init() {
        if (dataSource != null) {
            dataSource.close();
        }
        HikariConfig hikariConfig = new HikariConfig();
        if (configLoader.isUseLocal()) {
            File dbFile = new File(plugin.getDataFolder(), configLoader.getLocal().getOrDefault("filename", "data.db").toString());
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } else {
            Map<String, Object> remote = configLoader.getRemote();
            hikariConfig.setJdbcUrl(remote.get("url").toString());
            hikariConfig.setUsername(remote.get("username").toString());
            hikariConfig.setPassword(remote.get("password").toString());
            hikariConfig.setMinimumIdle((int) remote.getOrDefault("minimum-idle", 2));
            hikariConfig.setMaximumPoolSize((int) remote.getOrDefault("maximum-pool-size", 10));
            hikariConfig.setConnectionTimeout(Long.parseLong(remote.getOrDefault("connection-timeout", 30000).toString()));
        }
        dataSource = new HikariDataSource(hikariConfig);
        createTableIfNotExists();
    }

    private void createTableIfNotExists() {
        String dbType = "";
        try (Connection conn = dataSource.getConnection()) {
            dbType = conn.getMetaData().getDatabaseProductName().toLowerCase();
            String valueType = dbType.contains("postgresql") ? "DOUBLE PRECISION" : "DOUBLE";
            String sql = "CREATE TABLE IF NOT EXISTS scoreboard_data (" +
                    "server_name VARCHAR(64)," +
                    "scoreboard_name VARCHAR(64)," +
                    "string VARCHAR(255)," +
                    "value " + valueType + "," +
                    "push BOOLEAN DEFAULT TRUE," +
                    "PRIMARY KEY (server_name, scoreboard_name, string)" +
                    ")";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create table: " + e.getMessage());
        }
    }

    public void ensureTableExists() {
        createTableIfNotExists();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
