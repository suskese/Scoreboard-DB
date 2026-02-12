package me.mklv.scoreboarddb;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScoreboardDBCommand implements TabExecutor {
    private final ScoreboardDBPlugin plugin;
    private final DatabaseManager dbManager;

    public ScoreboardDBCommand(ScoreboardDBPlugin plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.dbManager = dbManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /scoreboarddb <push|pull|sync-now|get|mode|refresh-cache> ...");
            sender.sendMessage("§6push <scoreboard> [string] [value] - Push to database");
            sender.sendMessage("§6pull <scoreboard> [string] - Pull from database");
            sender.sendMessage("§6get <scoreboard> [string] [--database] - Get values");
            sender.sendMessage("§6mode <scoreboard> <push-only|pull-only|default> - Set sync mode");
            sender.sendMessage("§6sync-now - Sync immediately");
            sender.sendMessage("§6refresh-cache - Clear placeholder cache");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "push":
                return handlePush(sender, args);
            case "pull":
                return handlePull(sender, args);
            case "sync-now":
                return handleSyncNow(sender, args);
            case "get":
                return handleGet(sender, args);
            case "refresh-cache":
                return handleRefreshCache(sender, args);
            case "mode":
                return handleMode(sender, args);
            default:
                sender.sendMessage("§cUnknown subcommand: " + sub);
                return true;
        }
    }

    private boolean handlePush(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /scoreboarddb push <scoreboard> [string] [value]");
            return true;
        }

        String scoreboard = args[1];
        
        // Case 1: Push entire scoreboard (no string arg)
        if (args.length == 2) {
            return pushEntireScoreboard(sender, scoreboard);
        }
        
        // Case 2: Push specific entry or selector with optional value
        String targetArg = args[2];
        Double manualValue = null;
        if (args.length >= 4) {
            try {
                manualValue = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cValue must be a number.");
                return true;
            }
        }

        if (targetArg.startsWith("@")) {
            Set<String> targets = resolveTargets(sender, targetArg);
            if (targets.isEmpty()) {
                sender.sendMessage("§cNo players matched selector.");
                return true;
            }
            return pushTargets(sender, scoreboard, targets, manualValue);
        }

        // Non-selector: keep legacy behavior
        if (manualValue == null) {
            return pushFromLocal(sender, scoreboard, targetArg);
        }

        return pushSingleValue(sender, scoreboard, targetArg, manualValue);
    }

    private Set<String> resolveTargets(CommandSender sender, String targetArg) {
        Set<String> targets = new HashSet<>();
        try {
            List<Entity> entities = Bukkit.selectEntities(sender, targetArg);
            for (Entity entity : entities) {
                if (entity instanceof Player) {
                    targets.add(entity.getName());
                }
            }
        } catch (IllegalArgumentException | NoSuchMethodError ignored) {
            // Fallback to literal
        }
        return targets;
    }

    private boolean pushTargets(CommandSender sender, String scoreboard, Set<String> targets, Double manualValue) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            sender.sendMessage("§cCannot access local scoreboard.");
            return true;
        }

        Scoreboard sb = manager.getMainScoreboard();
        Objective obj = sb.getObjective(scoreboard);
        if (manualValue == null && obj == null) {
            sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist locally.");
            return true;
        }

        String serverName = plugin.getServerName();
        String sql = dbManager.getScoreboardDataUpsertSql();
        int count = 0;
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String target : targets) {
                    double valueToSave;
                    if (manualValue != null) {
                        valueToSave = manualValue;
                    } else {
                        try {
                            Score score = obj.getScore(target);
                            if (!score.isScoreSet()) {
                                continue;
                            }
                            valueToSave = score.getScore();
                        } catch (IllegalStateException ignore) {
                            continue;
                        }
                    }
                    ps.setString(1, serverName);
                    ps.setString(2, scoreboard);
                    ps.setString(3, target);
                    ps.setDouble(4, valueToSave);
                    ps.setBoolean(5, true);
                    ps.addBatch();
                    count++;
                }
                if (count > 0) {
                    ps.executeBatch();
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§cFailed to push values: " + e.getMessage());
            return true;
        }

        if (count == 0) {
            sender.sendMessage("§cNo scores found to push.");
        } else {
            sender.sendMessage("§aPushed §b" + count + "§a entries to database.");
        }
        return true;
    }

    private boolean pushSingleValue(CommandSender sender, String scoreboard, String key, double value) {
        String serverName = plugin.getServerName();
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = dbManager.getScoreboardDataUpsertSql();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboard);
                ps.setString(3, key);
                ps.setDouble(4, value);
                ps.setBoolean(5, true);
                ps.executeUpdate();
            }
            sender.sendMessage("§aValue pushed to database.");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("scoreboard_data")) {
                dbManager.ensureTableExists();
                sender.sendMessage("§eTable was missing and has been created. Please try again.");
            } else {
                sender.sendMessage("§cFailed to push value: " + e.getMessage());
            }
        }
        return true;
    }

    private boolean pushFromLocal(CommandSender sender, String scoreboard, String key) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            sender.sendMessage("§cCannot access local scoreboard.");
            return true;
        }

        Scoreboard sb = manager.getMainScoreboard();
        Objective obj = sb.getObjective(scoreboard);
        if (obj == null) {
            sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist locally.");
            return true;
        }

        try {
            if (!obj.getScore(key).isScoreSet()) {
                sender.sendMessage("§cValue for '" + key + "' is not set on this scoreboard.");
                return true;
            }
            int value = obj.getScore(key).getScore();
            
            String serverName = plugin.getServerName();
            try (Connection conn = dbManager.getDataSource().getConnection()) {
                String sql = dbManager.getScoreboardDataUpsertSql();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, serverName);
                    ps.setString(2, scoreboard);
                    ps.setString(3, key);
                    ps.setDouble(4, value);
                    ps.setBoolean(5, true);
                    ps.executeUpdate();
                }
                sender.sendMessage("§aValue pushed to database: " + value);
            } catch (Exception e) {
                sender.sendMessage("§cFailed to push value: " + e.getMessage());
            }
        } catch (IllegalStateException e) {
            sender.sendMessage("§cValue for '" + key + "' is not set on this scoreboard.");
        }
        return true;
    }

    private boolean pushEntireScoreboard(CommandSender sender, String scoreboard) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            sender.sendMessage("§cCannot access local scoreboard.");
            return true;
        }

        Scoreboard sb = manager.getMainScoreboard();
        Objective obj = sb.getObjective(scoreboard);
        if (obj == null) {
            sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist locally.");
            return true;
        }

        String serverName = plugin.getServerName();
        int count = 0;
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = dbManager.getScoreboardDataUpsertSql();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String entry : sb.getEntries()) {
                    try {
                        if (obj.getScore(entry).isScoreSet()) {
                            int value = obj.getScore(entry).getScore();
                            ps.setString(1, serverName);
                            ps.setString(2, scoreboard);
                            ps.setString(3, entry);
                            ps.setDouble(4, value);
                            ps.setBoolean(5, true);
                            ps.addBatch();
                            count++;
                        }
                    } catch (IllegalStateException ignore) {
                        // Entry does not have a score for this objective
                    }
                }
                if (count > 0) {
                    ps.executeBatch();
                }
            }
            sender.sendMessage("§aPushed §b" + count + "§a entries from scoreboard '" + scoreboard + "' to database.");
        } catch (Exception e) {
            sender.sendMessage("§cFailed to push scoreboard: " + e.getMessage());
        }
        return true;
    }

    private boolean handlePull(CommandSender sender, String[] args) {
        // /scoreboarddb pull <scoreboard> [string] - pull single or all values from DB to local scoreboard
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /scoreboarddb pull <scoreboard> [string]");
            return true;
        }
        String scoreboard = args[1];
        
        // Case 1: Pull entire scoreboard (no string arg)
        if (args.length == 2) {
            return pullEntireScoreboard(sender, scoreboard);
        }
        
        // Case 2: Pull specific entry
        String key = args[2];
        String serverName = plugin.getServerName();

        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboard);
                ps.setString(3, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        double value = rs.getDouble("value");
                        ScoreboardManager manager = Bukkit.getScoreboardManager();
                        if (manager != null) {
                            Scoreboard sb = manager.getMainScoreboard();
                            Objective obj = sb.getObjective(scoreboard);
                            if (obj != null) {
                                obj.getScore(key).setScore((int) value);
                                sender.sendMessage("§aValue pulled from database: " + (int) value);
                            } else {
                                sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist.");
                            }
                        }
                    } else {
                        sender.sendMessage("§cNo value found in database.");
                    }
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§cFailed to pull value: " + e.getMessage());
        }
        return true;
    }

    private boolean pullEntireScoreboard(CommandSender sender, String scoreboard) {
        String serverName = plugin.getServerName();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            sender.sendMessage("§cCannot access local scoreboard.");
            return true;
        }

        Scoreboard sb = manager.getMainScoreboard();
        Objective obj = sb.getObjective(scoreboard);
        if (obj == null) {
            sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist.");
            return true;
        }

        int count = 0;
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT string, value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboard);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String entry = rs.getString("string");
                        int value = (int) rs.getDouble("value");
                        obj.getScore(entry).setScore(value);
                        count++;
                    }
                }
            }
            sender.sendMessage("§aPulled §b" + count + "§a entries from database into scoreboard '" + scoreboard + "'.");
        } catch (Exception e) {
            sender.sendMessage("§cFailed to pull scoreboard: " + e.getMessage());
        }
        return true;
    }

    private boolean handleSyncNow(CommandSender sender, String[] args) {
        plugin.syncDatabase();
        sender.sendMessage("§aSync triggered.");
        return true;
    }

    private boolean handleGet(CommandSender sender, String[] args) {
        // /scoreboarddb get <scoreboard> [string] [--database]
        // Without string: get all values in scoreboard
        // With string: get specific value
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /scoreboarddb get <scoreboard> [string] [--database]");
            return true;
        }
        String scoreboard = args[1];
        boolean fromDatabase = args.length > 2 && (args[args.length - 1].equalsIgnoreCase("--database"));
        
        // Case 1: Get entire scoreboard
        if (args.length == 2 || (args.length == 3 && fromDatabase)) {
            return getEntireScoreboard(sender, scoreboard, fromDatabase);
        }
        
        // Case 2: Get specific entry
        String key = args[2];
        
        if (fromDatabase) {
            String serverName = plugin.getServerName();
            try (Connection conn = dbManager.getDataSource().getConnection()) {
                String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, serverName);
                    ps.setString(2, scoreboard);
                    ps.setString(3, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            double val = rs.getDouble("value");
                            sender.sendMessage("§aValue (Database): §6" + (int) val);
                        } else {
                            sender.sendMessage("§cNo value found in database.");
                        }
                    }
                }
            } catch (Exception e) {
                sender.sendMessage("§cFailed to get value: " + e.getMessage());
            }
        } else {
            // Get from local scoreboard
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                Scoreboard sb = manager.getMainScoreboard();
                Objective obj = sb.getObjective(scoreboard);
                if (obj != null) {
                    try {
                        if (obj.getScore(key).isScoreSet()) {
                            int val = obj.getScore(key).getScore();
                            sender.sendMessage("§aValue (Local): §6" + val);
                        } else {
                            sender.sendMessage("§cNo value found on local scoreboard.");
                        }
                    } catch (IllegalStateException e) {
                        sender.sendMessage("§cNo value found on local scoreboard.");
                    }
                } else {
                    sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist locally.");
                }
            }
        }
        return true;
    }

    private boolean getEntireScoreboard(CommandSender sender, String scoreboard, boolean fromDatabase) {
        if (fromDatabase) {
            String serverName = plugin.getServerName();
            sender.sendMessage("§6─────────────────────────────────");
            sender.sendMessage("§6Scoreboard: §a" + scoreboard + " §6(Database)");
            sender.sendMessage("§6─────────────────────────────────");
            try (Connection conn = dbManager.getDataSource().getConnection()) {
                String sql = "SELECT string, value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? ORDER BY string";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, serverName);
                    ps.setString(2, scoreboard);
                    try (ResultSet rs = ps.executeQuery()) {
                        int count = 0;
                        while (rs.next()) {
                            String name = rs.getString("string");
                            int value = (int) rs.getDouble("value");
                            sender.sendMessage("§a" + name + ": §6" + value);
                            count++;
                        }
                        if (count == 0) {
                            sender.sendMessage("§cNo entries found in database.");
                        }
                    }
                }
            } catch (Exception e) {
                sender.sendMessage("§cFailed to get scoreboard: " + e.getMessage());
            }
        } else {
            // Get from local scoreboard
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                Scoreboard sb = manager.getMainScoreboard();
                Objective obj = sb.getObjective(scoreboard);
                if (obj != null) {
                    sender.sendMessage("§6─────────────────────────────────");
                    sender.sendMessage("§6Scoreboard: §a" + scoreboard + " §6(Local)");
                    sender.sendMessage("§6─────────────────────────────────");
                    int count = 0;
                    for (String entry : sb.getEntries()) {
                        try {
                            if (obj.getScore(entry).isScoreSet()) {
                                int val = obj.getScore(entry).getScore();
                                sender.sendMessage("§a" + entry + ": §6" + val);
                                count++;
                            }
                        } catch (IllegalStateException ignore) {
                            // Entry does not have score for this objective
                        }
                    }
                    if (count == 0) {
                        sender.sendMessage("§cNo entries found on local scoreboard.");
                    }
                } else {
                    sender.sendMessage("§cScoreboard '" + scoreboard + "' does not exist locally.");
                }
            }
        }
        return true;
    }

    private boolean handleRefreshCache(CommandSender sender, String[] args) {
        plugin.refreshPlaceholders();
        sender.sendMessage("§aPlaceholder cache refreshed.");
        return true;
    }

    private boolean handleMode(CommandSender sender, String[] args) {
        // /scoreboarddb mode <scoreboard> <push-only | pull-only | default>
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /scoreboarddb mode <scoreboard> <push-only|pull-only|default>");
            return true;
        }
        
        String scoreboard = args[1];
        String mode = args[2].toLowerCase();
        
        // Validate mode
        if (!mode.equals("push-only") && !mode.equals("pull-only") && !mode.equals("default")) {
            sender.sendMessage("§cInvalid mode! Use: push-only, pull-only, or default");
            return true;
        }
        
        // Map user-friendly names to internal names
        String dbMode = mode;
        if (mode.equals("push-only")) {
            dbMode = "push";
        } else if (mode.equals("pull-only")) {
            dbMode = "pull";
        }
        
        String serverName = plugin.getServerName();
        try {
            dbManager.setScoreboardMode(serverName, scoreboard, dbMode);
            sender.sendMessage("§aScoreboard '" + scoreboard + "' mode set to §b" + mode);
        } catch (Exception e) {
            sender.sendMessage("§cFailed to set mode: " + e.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            Collections.addAll(subs, "push", "pull", "sync-now", "get", "mode", "refresh-cache");
            return filterSuggestions(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        // Tab complete for push command
        if (sub.equals("push")) {
            if (args.length == 2) {
                return filterSuggestions(getBukkitScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("@a");
                suggestions.add("@p");
                suggestions.addAll(getBukkitEntryNames(args[1]));
                return filterSuggestions(suggestions, args[2]);
            }
        }

        // Tab complete for pull command
        if (sub.equals("pull")) {
            if (args.length == 2) {
                return filterSuggestions(getDatabaseScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                return filterSuggestions(getDatabaseEntryNames(args[1]), args[2]);
            }
        }

        // Tab complete for get command
        if (sub.equals("get")) {
            if (args.length == 2) {
                return filterSuggestions(getDatabaseScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                return filterSuggestions(getDatabaseEntryNames(args[1]), args[2]);
            } else if (args.length == 4) {
                List<String> flags = new ArrayList<>();
                Collections.addAll(flags, "--database");
                return filterSuggestions(flags, args[3]);
            }
        }
        
        // Tab complete for mode command
        if (sub.equals("mode")) {
            if (args.length == 2) {
                return filterSuggestions(getDatabaseScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                List<String> modes = new ArrayList<>();
                Collections.addAll(modes, "push-only", "pull-only", "default");
                return filterSuggestions(modes, args[2]);
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

    private List<String> getDatabaseScoreboardNames() {
        List<String> scoreboards = new ArrayList<>();
        String serverName = plugin.getServerName();
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
            // Silently fail
        }
        return scoreboards;
    }

    private List<String> getDatabaseEntryNames(String scoreboardName) {
        List<String> entries = new ArrayList<>();
        String serverName = plugin.getServerName();
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
            // Silently fail
        }
        return entries;
    }
}