package me.mklv.scoreboarddb;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Objective;
// import org.bukkit.scoreboard.Score;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScoreboardDBCommand implements TabExecutor {
    private final DatabaseManager dbManager;

    public ScoreboardDBCommand(ScoreboardDBPlugin plugin, DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /scoreboarddb <save|get|sync-now> ...");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "save":
                // /scoreboarddb save <scoreboard> <string> <value>
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /scoreboarddb save <scoreboard> <string> <value>");
                    return true;
                }
                String scoreboard = args[1];
                String key = args[2];
                double value;
                try {
                    value = Double.parseDouble(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cValue must be a number.");
                    return true;
                }
                String serverName = ScoreboardDBPlugin.getInstance().getServerName();
                try (Connection conn = dbManager.getDataSource().getConnection()) {
                    String sql = "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) VALUES (?, ?, ?, ?, ?) " +
                            "ON CONFLICT(server_name, scoreboard_name, string) DO UPDATE SET value = excluded.value, push = excluded.push";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, serverName);
                        ps.setString(2, scoreboard);
                        ps.setString(3, key);
                        ps.setDouble(4, value);
                        ps.setBoolean(5, false); // When saving from Minecraft, set push to false
                        ps.executeUpdate();
                    }
                    sender.sendMessage("§aValue saved.");
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("relation \"scoreboard_data\" does not exist")) {
                        dbManager.ensureTableExists();
                        sender.sendMessage("§eTable was missing and has been created. Please try again.");
                    } else {
                        sender.sendMessage("§cFailed to save value: " + e.getMessage());
                    }
                }
                break;
            case "get":
                // /scoreboarddb get <scoreboard> <string>
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /scoreboarddb get <scoreboard> <string>");
                    return true;
                }
                scoreboard = args[1];
                key = args[2];
                serverName = ScoreboardDBPlugin.getInstance().getServerName();
                try (Connection conn = dbManager.getDataSource().getConnection()) {
                    String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, serverName);
                        ps.setString(2, scoreboard);
                        ps.setString(3, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                double val = rs.getDouble("value");
                                sender.sendMessage("§aValue: " + val);
                            } else {
                                sender.sendMessage("§cNo value found.");
                            }
                        }
                    }
                } catch (Exception e) {
                    sender.sendMessage("§cFailed to get value: " + e.getMessage());
                }
                break;
            case "sync-now":
                ScoreboardDBPlugin.getInstance().syncDatabase();
                sender.sendMessage("§aSync triggered.");
                break;
            default:
                sender.sendMessage("§cUnknown subcommand");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            Collections.addAll(subs, "save", "get", "sync-now");
            return filterSuggestions(subs, args[0]);
        }
        // Tab complete for save command
        if (args[0].equalsIgnoreCase("save")) {
            if (args.length == 2) {
                return filterSuggestions(getBukkitScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                return filterSuggestions(getBukkitEntryNames(args[1]), args[2]);
            }
        }
        // Tab complete for get command
        if (args[0].equalsIgnoreCase("get")) {
            if (args.length == 2) {
                return filterSuggestions(getDatabaseScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                return filterSuggestions(getDatabaseEntryNames(args[1]), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterSuggestions(List<String> suggestions, String input) {
        List<String> filtered = new ArrayList<>();
        String lowerInput = input.toLowerCase();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(lowerInput)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }

    private List<String> getBukkitScoreboardNames() {
        List<String> scoreboards = new ArrayList<>();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return scoreboards;
        Scoreboard scoreboard = manager.getMainScoreboard();
        for (Objective obj : scoreboard.getObjectives()) {
            scoreboards.add(obj.getName());
        }
        return scoreboards;
    }

    private List<String> getBukkitEntryNames(String scoreboardName) {
        List<String> entries = new ArrayList<>();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return entries;
        Scoreboard scoreboard = manager.getMainScoreboard();
        Objective obj = scoreboard.getObjective(scoreboardName);
        if (obj != null) {
            // Get all entries and filter to only those with scores in this objective
            for (String entry : scoreboard.getEntries()) {
                try {
                    if (obj.getScore(entry).isScoreSet()) {
                        entries.add(entry);
                    }
                } catch (IllegalStateException ignore) {
                    // Entry does not have a score for this objective
                }
            }
        }
        return entries;
    }

    private List<String> getScoreboardNames() {
        List<String> scoreboards = new ArrayList<>();
        String serverName = ScoreboardDBPlugin.getInstance().getServerName();
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT DISTINCT scoreboard_name FROM scoreboard_data WHERE server_name = ? ORDER BY scoreboard_name";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scoreboards.add(rs.getString("scoreboard_name"));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail, return empty list
        }
        return scoreboards;
    }

    private List<String> getDatabaseScoreboardNames() {
        return getScoreboardNames();
    }

    private List<String> getDatabaseEntryNames(String scoreboardName) {
        List<String> entries = new ArrayList<>();
        String serverName = ScoreboardDBPlugin.getInstance().getServerName();
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT DISTINCT string FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? ORDER BY string";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(rs.getString("string"));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail, return empty list
        }
        return entries;
    }
}