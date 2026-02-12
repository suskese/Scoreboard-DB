package me.mklv.scoreboarddb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatabaseManager {
    private final Plugin plugin;
    private final ConfigLoader configLoader;
    private HikariDataSource dataSource;
    private String databaseProductNameLower;

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
        databaseProductNameLower = detectDatabaseProductName();
        createTableIfNotExists();
    }

    private String detectDatabaseProductName() {
        try (Connection conn = dataSource.getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            return name != null ? name.toLowerCase() : "";
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to detect database type: " + e.getMessage());
            return "";
        }
    }

    private boolean isMySqlLike() {
        if (databaseProductNameLower == null) return false;
        return databaseProductNameLower.contains("mysql") || databaseProductNameLower.contains("mariadb");
    }

    public String getScoreboardDataUpsertSql() {
        if (isMySqlLike()) {
            return "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE value = VALUES(value), push = VALUES(push)";
        }
        return "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(server_name, scoreboard_name, string) DO UPDATE SET value = excluded.value, push = excluded.push";
    }

    public String getScoreboardSettingsUpsertSql() {
        String base = "INSERT INTO scoreboard_settings (server_name, scoreboard_name, sync_status, entry_count) " +
                "VALUES (?, ?, ?, (SELECT COUNT(*) FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ?)) ";
        if (isMySqlLike()) {
            return base + "ON DUPLICATE KEY UPDATE sync_status = VALUES(sync_status), entry_count = VALUES(entry_count)";
        }
        return base + "ON CONFLICT(server_name, scoreboard_name) DO UPDATE SET " +
                "sync_status = excluded.sync_status, entry_count = excluded.entry_count";
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
            
            // Create scoreboard settings table
            String settingsSql = "CREATE TABLE IF NOT EXISTS scoreboard_settings (" +
                    "server_name VARCHAR(64)," +
                    "scoreboard_name VARCHAR(64)," +
                    "sync_status VARCHAR(16) DEFAULT 'none'," +
                    "entry_count INT DEFAULT 0," +
                    "PRIMARY KEY (server_name, scoreboard_name)" +
                    ")";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(settingsSql);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create table: " + e.getMessage());
        }
    }

    public void ensureTableExists() {
        createTableIfNotExists();
    }

    public void populateScoreboardSettings(String serverName) {
        try (Connection conn = dataSource.getConnection()) {
            // Get all distinct scoreboards from scoreboard_data
            String selectSql = "SELECT DISTINCT scoreboard_name FROM scoreboard_data WHERE server_name = ?";
            List<String> scoreboards = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, serverName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scoreboards.add(rs.getString("scoreboard_name"));
                    }
                }
            }
            
            // Insert or update scoreboard_settings for each scoreboard
            String insertSql = getScoreboardSettingsUpsertSql();
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (String scoreboard : scoreboards) {
                    ps.setString(1, serverName);
                    ps.setString(2, scoreboard);
                    ps.setString(3, "default");
                    ps.setString(4, serverName);
                    ps.setString(5, scoreboard);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to populate scoreboard settings: " + e.getMessage());
        }
    }

    public void setScoreboardMode(String serverName, String scoreboardName, String mode) throws SQLException {
        String sql = getScoreboardSettingsUpsertSql();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                ps.setString(3, mode.toLowerCase());
                ps.setString(4, serverName);
                ps.setString(5, scoreboardName);
                ps.executeUpdate();
            }
        }
    }

    public String getScoreboardMode(String serverName, String scoreboardName) throws SQLException {
        String sql = "SELECT sync_status FROM scoreboard_settings WHERE server_name = ? AND scoreboard_name = ?";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("sync_status");
                    }
                }
            }
        }
        return "default"; // Default mode if not found
    }

    public int updateScoreboardEntryCount(String serverName, String scoreboardName) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ?";
        int count = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getInt("count");
                    }
                }
            }
            
            String updateSql = "UPDATE scoreboard_settings SET entry_count = ? WHERE server_name = ? AND scoreboard_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, count);
                ps.setString(2, serverName);
                ps.setString(3, scoreboardName);
                ps.executeUpdate();
            }
        }
        return count;
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
