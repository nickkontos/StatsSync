package com.homeofcappuccino.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import com.google.gson.stream.JsonReader;
import org.bukkit.Bukkit;
import java.util.Collections;
import java.util.Arrays;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Score;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Plugin: StatsSync
 * Purpose: Exports and syncs player statistics to JSON/scoreboard and optional Plan extensions.
 * Notes: Built for Paper 1.21.x (Java 21).
 */

public class StatsSyncPlugin extends JavaPlugin {
    private static final String PLAN_DB = "/data/plugins/Plan/database.db";
    private static final String WHITELIST_PATH = "/data/whitelist.json";

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ReentrantLock dbLock = new ReentrantLock();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scheduleTasks();
    }

    private void scheduleTasks() {
        if (getConfig().getBoolean("planExtensions.enabled", true)) {
            scheduleAsyncTask("planExtensions", this::refreshPlanExtensions);
        }
        if (getConfig().getBoolean("playerStats.enabled", true)) {
            scheduleAsyncTask("playerStats", this::exportPlayerStatsJson);
        }
        if (getConfig().getBoolean("webstats.enabled", true)) {
            scheduleAsyncTask("webstats", this::syncWebstatsScoreboard);
        }
    }

    private void scheduleAsyncTask(String key, Runnable task) {
        long initialDelaySeconds = getConfig().getLong(key + ".initialDelaySeconds", 180);
        long intervalMinutes = getConfig().getLong(key + ".intervalMinutes", 15);
        long initialDelayTicks = TimeUnit.SECONDS.toSeconds(initialDelaySeconds) * 20L;
        long periodTicks = TimeUnit.MINUTES.toSeconds(intervalMinutes) * 20L;

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                task.run();
            } catch (Exception ex) {
                getLogger().warning(key + " task failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, initialDelayTicks, periodTicks);
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("statssync")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /statssync reload | /statssync run <webstats|playerstats|planextensions>");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            reloadConfig();
            Bukkit.getScheduler().cancelTasks(this);
            scheduleTasks();
            sender.sendMessage("StatsSync reloaded.");
            return true;
        }
        if (sub.equals("run")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /statssync run <webstats|playerstats|planextensions>");
                return true;
            }
            String target = args[1].toLowerCase();
            switch (target) {
                case "webstats":
                    runTaskNow("webstats", this::syncWebstatsScoreboard, sender);
                    return true;
                case "playerstats":
                    runTaskNow("playerstats", this::exportPlayerStatsJson, sender);
                    return true;
                case "planextensions":
                    runTaskNow("planextensions", this::refreshPlanExtensions, sender);
                    return true;
                default:
                    sender.sendMessage("Unknown target: " + target);
                    return true;
            }
        }
        sender.sendMessage("Unknown subcommand: " + sub);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("statssync")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("reload", "run");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("run")) {
            return Arrays.asList("webstats", "playerstats", "planextensions");
        }
        return Collections.emptyList();
    }

    private void runTaskNow(String key, Runnable task, CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String message;
            try {
                task.run();
                message = "StatsSync: " + key + " completed.";
            } catch (Exception ex) {
                message = "StatsSync: " + key + " failed: " + ex.getMessage();
            }
            String finalMessage = message;
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(finalMessage));
        });
    }

    private static class PlayerEntry {
        final String name;
        final String uuid;

        PlayerEntry(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    private List<PlayerEntry> loadWhitelist() {
        Path path = Path.of(WHITELIST_PATH);
        if (!Files.exists(path)) {
            getLogger().warning("Whitelist file not found: " + WHITELIST_PATH);
            return List.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonArray()) {
                return List.of();
            }
            List<PlayerEntry> entries = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                JsonObject obj = entry.getAsJsonObject();
                if (!obj.has("name") || !obj.has("uuid")) {
                    continue;
                }
                String name = obj.get("name").getAsString();
                if (!isAllowedName(name)) {
                    continue;
                }
                entries.add(new PlayerEntry(name, obj.get("uuid").getAsString()));
            }
            return entries;
        } catch (IOException ex) {
            getLogger().warning("Failed to read whitelist: " + ex.getMessage());
            return List.of();
        }
    }

    private boolean isAllowedName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return !(name.startsWith("<") && name.endsWith(">"));
    }

    private JsonObject readJsonFile(Path path) {
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (IOException ex) {
            return new JsonObject();
        }
    }

    private JsonObject getNested(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private long sumDistance(JsonObject custom) {
        long total = 0L;
        for (Map.Entry<String, JsonElement> entry : custom.entrySet()) {
            if (entry.getKey().endsWith("_one_cm")) {
                total += entry.getValue().getAsLong();
            }
        }
        return total;
    }

    private int countMined(JsonObject mined) {
        int total = 0;
        for (Map.Entry<String, JsonElement> entry : mined.entrySet()) {
            total += entry.getValue().getAsInt();
        }
        return total;
    }

    private Map<String, Integer> countAdvancements(JsonObject advRoot) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("adv_story", 0);
        counts.put("adv_nether", 0);
        counts.put("adv_end", 0);
        counts.put("adv_adventure", 0);
        counts.put("adv_husbandry", 0);
        int total = 0;

        for (Map.Entry<String, JsonElement> entry : advRoot.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject adv = entry.getValue().getAsJsonObject();
            JsonElement done = adv.get("done");
            if (done == null || !done.getAsBoolean()) {
                continue;
            }
            total++;
            String key = entry.getKey();
            if (key.startsWith("minecraft:story/")) {
                counts.put("adv_story", counts.get("adv_story") + 1);
            } else if (key.startsWith("minecraft:nether/")) {
                counts.put("adv_nether", counts.get("adv_nether") + 1);
            } else if (key.startsWith("minecraft:end/")) {
                counts.put("adv_end", counts.get("adv_end") + 1);
            } else if (key.startsWith("minecraft:adventure/")) {
                counts.put("adv_adventure", counts.get("adv_adventure") + 1);
            } else if (key.startsWith("minecraft:husbandry/")) {
                counts.put("adv_husbandry", counts.get("adv_husbandry") + 1);
            }
        }
        counts.put("adv_total", total);
        return counts;
    }

    private Connection openPlanDb() throws SQLException {
        String type = getConfig().getString("planDb.type", "sqlite");
        if (type != null && type.equalsIgnoreCase("mysql")) {
            String host = getConfig().getString("planDb.host", "plan-db");
            int port = getConfig().getInt("planDb.port", 3306);
            String database = getConfig().getString("planDb.database", "plan");
            String user = getConfig().getString("planDb.user", "plan");
            String password = getConfig().getString("planDb.password", "");
            String options = getConfig().getString("planDb.options", "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + options;
            return DriverManager.getConnection(url, user, password);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + PLAN_DB);
        try (PreparedStatement stmt = conn.prepareStatement("PRAGMA busy_timeout=30000")) {
            stmt.execute();
        }
        return conn;
    }

    private void refreshPlanExtensions() {
        if (!dbLock.tryLock()) {
            getLogger().warning("planExtensions skipped due to busy lock");
            return;
        }
        try (Connection conn = openPlanDb()) {
            Map<String, Integer> providers = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("select id, name from plan_extension_providers")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    providers.put(rs.getString(2), rs.getInt(1));
                }
            }

            if (providers.isEmpty()) {
                getLogger().warning("planExtensions: no providers found");
                return;
            }

            Map<String, Integer> userIdByUuid = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("select id, uuid from plan_users")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    userIdByUuid.put(rs.getString(2), rs.getInt(1));
                }
            }

            Map<Integer, Integer> sessionCounts = new HashMap<>();
            Map<Integer, Long> afkSums = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("select user_id, count(*) from plan_sessions group by user_id")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    sessionCounts.put(rs.getInt(1), rs.getInt(2));
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("select user_id, coalesce(sum(afk_time), 0) from plan_sessions group by user_id")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    afkSums.put(rs.getInt(1), rs.getLong(2));
                }
            }

            List<PlayerEntry> players = loadWhitelist();
            List<Object[]> rows = new ArrayList<>();

            for (PlayerEntry player : players) {
                Path statsPath = Path.of("/data/world/stats/" + player.uuid + ".json");
                Path advPath = Path.of("/data/world/advancements/" + player.uuid + ".json");
                JsonObject statsRoot = readJsonFile(statsPath);
                JsonObject advRoot = readJsonFile(advPath);

                JsonObject stats = getNested(statsRoot, "stats");
                JsonObject custom = getNested(stats, "minecraft:custom");
                JsonObject mined = getNested(stats, "minecraft:mined");

                Map<String, Integer> adv = countAdvancements(advRoot);

                int deaths = custom.has("minecraft:deaths") ? custom.get("minecraft:deaths").getAsInt() : 0;
                int mobKills = custom.has("minecraft:mob_kills") ? custom.get("minecraft:mob_kills").getAsInt() : 0;
                int playerKills = custom.has("minecraft:player_kills") ? custom.get("minecraft:player_kills").getAsInt() : 0;
                int damageDealt = custom.has("minecraft:damage_dealt") ? custom.get("minecraft:damage_dealt").getAsInt() : 0;
                int damageTaken = custom.has("minecraft:damage_taken") ? custom.get("minecraft:damage_taken").getAsInt() : 0;
                int timeSinceDeath = custom.has("minecraft:time_since_death") ? custom.get("minecraft:time_since_death").getAsInt() : 0;
                int villagerTrades = custom.has("minecraft:traded_with_villager") ? custom.get("minecraft:traded_with_villager").getAsInt() : 0;
                int distanceTotal = (int) sumDistance(custom);
                int blocksMined = countMined(mined);

                Integer userId = userIdByUuid.get(player.uuid);
                long afkTime = 0;
                if (userId != null) {
                    afkTime = afkSums.getOrDefault(userId, 0L);
                }

                Map<String, Integer> values = new HashMap<>();
                values.put("deaths", deaths);
                values.put("mob_kills", mobKills);
                values.put("adv_total", adv.getOrDefault("adv_total", 0));
                values.put("adv_story", adv.getOrDefault("adv_story", 0));
                values.put("adv_nether", adv.getOrDefault("adv_nether", 0));
                values.put("adv_end", adv.getOrDefault("adv_end", 0));
                values.put("adv_adventure", adv.getOrDefault("adv_adventure", 0));
                values.put("adv_husbandry", adv.getOrDefault("adv_husbandry", 0));
                values.put("player_kills", playerKills);
                values.put("damage_dealt", damageDealt);
                values.put("damage_taken", damageTaken);
                values.put("distance_moved", distanceTotal);
                values.put("distance_traveled", distanceTotal);
                values.put("blocks_mined", blocksMined);
                values.put("trades_with_villager", villagerTrades);
                values.put("time_since_death", timeSinceDeath);
                values.put("afk_time", (int) (afkTime));

                for (Map.Entry<String, Integer> entry : values.entrySet()) {
                    Integer providerId = providers.get(entry.getKey());
                    if (providerId == null) {
                        continue;
                    }
                    rows.add(new Object[] { entry.getValue(), player.uuid, providerId });
                }
            }

            String dbType = getConfig().getString("planDb.type", "sqlite");
            if (dbType == null || dbType.equalsIgnoreCase("sqlite")) {
                try (PreparedStatement pragmaStmt = conn.prepareStatement("PRAGMA busy_timeout=5000")) {
                    pragmaStmt.execute();
                }
            }
            conn.setAutoCommit(false);
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < providers.size(); i++) {
                if (i > 0) {
                    placeholders.append(",");
                }
                placeholders.append("?");
            }
            try (PreparedStatement deleteStmt = conn.prepareStatement(
                "delete from plan_extension_user_values where provider_id in (" + placeholders + ")")) {
                int index = 1;
                for (int providerId : providers.values()) {
                    deleteStmt.setInt(index++, providerId);
                }
                deleteStmt.executeUpdate();
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(
                "insert into plan_extension_user_values " +
                    "(boolean_value, double_value, percentage_value, long_value, string_value, component_value, group_value, uuid, provider_id) " +
                    "values (NULL, NULL, NULL, ?, NULL, NULL, NULL, ?, ?)")) {
                for (Object[] row : rows) {
                    insertStmt.setInt(1, (Integer) row[0]);
                    insertStmt.setString(2, (String) row[1]);
                    insertStmt.setInt(3, (Integer) row[2]);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
            }

            try (PreparedStatement updateStmt = conn.prepareStatement("update plan_extension_plugins set last_updated=?")) {
                updateStmt.setLong(1, Instant.now().toEpochMilli());
                updateStmt.executeUpdate();
            }

            conn.commit();
        } catch (SQLException ex) {
            getLogger().warning("planExtensions SQL error: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            dbLock.unlock();
        }
    }

    private void exportPlayerStatsJson() {
        if (!dbLock.tryLock()) {
            getLogger().warning("playerStats skipped due to busy lock");
            return;
        }
        try (Connection conn = openPlanDb()) {
            String url = getConfig().getString("playerStats.planApiUrl", "http://127.0.0.1:8804/v1/playersTable?server=Server%201");
            JsonObject payload = fetchJson(url);
            if (payload == null) {
                getLogger().warning("playerStats: failed to fetch Plan API");
                return;
            }

            JsonArray players = payload.getAsJsonArray("players");
            if (players == null) {
                getLogger().warning("playerStats: no players array in Plan API");
                return;
            }

            Map<Integer, String> iconNames = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("select id, name from plan_extension_icons")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    iconNames.put(rs.getInt(1), rs.getString(2));
                }
            }

            List<int[]> providerList = new ArrayList<>();
            List<JsonObject> descriptors = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                "select id, name, text, icon_id from plan_extension_providers " +
                    "where show_in_players_table=1 and hidden=0 order by id")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String name = rs.getString(2);
                    String text = rs.getString(3);
                    int iconId = rs.getInt(4);
                    String iconName = iconNames.getOrDefault(iconId, "chart");

                    JsonObject icon = new JsonObject();
                    icon.addProperty("family", "SOLID");
                    icon.addProperty("familyClass", "fa");
                    icon.addProperty("color", "NONE");
                    icon.addProperty("colorClass", "");
                    icon.addProperty("iconName", iconName);

                    JsonObject desc = new JsonObject();
                    desc.addProperty("name", name);
                    desc.addProperty("text", text);
                    desc.add("icon", icon);
                    desc.addProperty("priority", 0);
                    descriptors.add(desc);
                    providerList.add(new int[] { id, descriptors.size() - 1 });
                }
            }

            Map<String, Map<Integer, JsonObject>> values = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                "select uuid, provider_id, long_value, double_value, string_value, boolean_value " +
                    "from plan_extension_user_values")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String uuid = rs.getString(1);
                    int providerId = rs.getInt(2);
                    JsonElement value = null;
                    long longVal = rs.getLong(3);
                    if (!rs.wasNull()) {
                        value = gson.toJsonTree(longVal);
                    } else {
                        double doubleVal = rs.getDouble(4);
                        if (!rs.wasNull()) {
                            value = gson.toJsonTree(doubleVal);
                        } else {
                            String stringVal = rs.getString(5);
                            if (stringVal != null) {
                                value = gson.toJsonTree(stringVal);
                            } else {
                                boolean boolVal = rs.getBoolean(6);
                                if (!rs.wasNull()) {
                                    value = gson.toJsonTree(boolVal ? 1 : 0);
                                }
                            }
                        }
                    }

                    if (value == null) {
                        value = gson.toJsonTree(0);
                    }

                    values.computeIfAbsent(uuid, k -> new HashMap<>());
                    Map<Integer, JsonObject> map = values.get(uuid);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", "NUMBER");
                    entry.addProperty("value", value.getAsString());
                    map.put(providerId, entry);
                }
            }

            for (JsonElement playerEl : players) {
                JsonObject player = playerEl.getAsJsonObject();
                String uuid = player.has("playerUUID") ? player.get("playerUUID").getAsString() : null;
                if (uuid == null) {
                    continue;
                }
                JsonObject extValues = new JsonObject();
                for (JsonObject desc : descriptors) {
                    String name = desc.get("name").getAsString();
                    JsonObject descObj = new JsonObject();
                    descObj.addProperty("name", name);
                    descObj.addProperty("text", desc.get("text").getAsString());
                    descObj.add("icon", desc.get("icon").deepCopy());
                    descObj.addProperty("priority", 0);

                    int providerId = -1;
                    for (int[] arr : providerList) {
                        if (descriptors.get(arr[1]).get("name").getAsString().equals(name)) {
                            providerId = arr[0];
                            break;
                        }
                    }

                    JsonObject valueEntry = values.getOrDefault(uuid, Map.of()).get(providerId);
                    if (valueEntry == null) {
                        valueEntry = new JsonObject();
                        valueEntry.addProperty("type", "NUMBER");
                        valueEntry.addProperty("value", "0");
                    }

                    JsonObject full = new JsonObject();
                    full.add("description", descObj);
                    full.addProperty("type", "NUMBER");
                    full.addProperty("value", valueEntry.get("value").getAsString());
                    extValues.add(name, full);
                }
                player.add("extensionValues", extValues);
            }

            JsonArray descriptorArray = new JsonArray();
            for (JsonObject desc : descriptors) {
                descriptorArray.add(desc);
            }
            payload.add("extensionDescriptors", descriptorArray);
            payload.addProperty("timestamp", Instant.now().toEpochMilli());
            payload.addProperty("timestamp_f", Instant.now().toString());

            String exportPath = getConfig().getString("playerStats.exportPath", "/data/player-stats/data.json");
            Path outPath = Path.of(exportPath);
            Files.createDirectories(outPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
                gson.toJson(payload, writer);
            }
        } catch (Exception ex) {
            getLogger().warning("playerStats export failed: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            dbLock.unlock();
        }
    }

    private JsonObject fetchJson(String url) {
        HttpURLConnection conn = null;
        try {
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            byte[] raw;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                try (var in = conn.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                }
                raw = baos.toByteArray();
            }
                        if (raw.length >= 2 && (raw[0] == (byte) 0x1f) && (raw[1] == (byte) 0x8b)) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    raw = gis.readAllBytes();
                }
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8))) {
                reader.setLenient(true);
                JsonElement element = JsonParser.parseReader(reader);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            }
        } catch (IOException ex) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }


    private void syncWebstatsScoreboard() {
        List<PlayerEntry> players = loadWhitelist();
        if (players.isEmpty()) {
            return;
        }

        Map<String, Integer> userIdByUuid = new HashMap<>();
        Map<Integer, Integer> sessionCounts = new HashMap<>();
        Map<Integer, Long> afkSums = new HashMap<>();

        try (Connection conn = openPlanDb()) {
            try (PreparedStatement stmt = conn.prepareStatement("select id, uuid from plan_users")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    userIdByUuid.put(rs.getString(2), rs.getInt(1));
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("select user_id, count(*) from plan_sessions group by user_id")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    sessionCounts.put(rs.getInt(1), rs.getInt(2));
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("select user_id, coalesce(sum(afk_time), 0) from plan_sessions group by user_id")) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    afkSums.put(rs.getInt(1), rs.getLong(2));
                }
            }
        } catch (SQLException ex) {
            getLogger().warning("webstats SQL error: " + ex.getMessage());
        }

        Map<String, Map<String, Integer>> valuesByPlayer = new HashMap<>();
        for (PlayerEntry player : players) {
            Path statsPath = Path.of("/data/world/stats/" + player.uuid + ".json");
            Path advPath = Path.of("/data/world/advancements/" + player.uuid + ".json");
            JsonObject statsRoot = readJsonFile(statsPath);
            JsonObject advRoot = readJsonFile(advPath);
            JsonObject stats = getNested(statsRoot, "stats");
            JsonObject custom = getNested(stats, "minecraft:custom");
            JsonObject mined = getNested(stats, "minecraft:mined");

            Map<String, Integer> adv = countAdvancements(advRoot);

            int deaths = custom.has("minecraft:deaths") ? custom.get("minecraft:deaths").getAsInt() : 0;
            int mobKills = custom.has("minecraft:mob_kills") ? custom.get("minecraft:mob_kills").getAsInt() : 0;
            int playerKills = custom.has("minecraft:player_kills") ? custom.get("minecraft:player_kills").getAsInt() : 0;
            int damageDealt = custom.has("minecraft:damage_dealt") ? custom.get("minecraft:damage_dealt").getAsInt() : 0;
            int damageTaken = custom.has("minecraft:damage_taken") ? custom.get("minecraft:damage_taken").getAsInt() : 0;
            int timeSinceDeath = custom.has("minecraft:time_since_death") ? custom.get("minecraft:time_since_death").getAsInt() : 0;
            int villagerTrades = custom.has("minecraft:traded_with_villager") ? custom.get("minecraft:traded_with_villager").getAsInt() : 0;
            int horseCm = custom.has("minecraft:horse_one_cm") ? custom.get("minecraft:horse_one_cm").getAsInt() : 0;
            int aviateCm = custom.has("minecraft:aviate_one_cm") ? custom.get("minecraft:aviate_one_cm").getAsInt() : 0;
            int playTicks = custom.has("minecraft:play_time") ? custom.get("minecraft:play_time").getAsInt() : 0;

            int totalKills = mobKills + playerKills;
            int kdRatio = deaths > 0 ? (int) Math.round((totalKills / (double) deaths) * 100) : totalKills * 100;
            int hoursPlayed = playTicks > 0 ? (int) (playTicks / 20 / 3600) : 0;
            int hoursPerKill = totalKills > 0 ? (int) Math.round((hoursPlayed / (double) totalKills) * 100) : 0;

            int distanceMeters = (int) (sumDistance(custom) / 100);
            int blocksMined = countMined(mined);

            Integer userId = userIdByUuid.get(player.uuid);
            int sessions = 0;
            int afkSeconds = 0;
            if (userId != null) {
                sessions = sessionCounts.getOrDefault(userId, 0);
                afkSeconds = (int) (afkSums.getOrDefault(userId, 0L) / 1000);
            }

            Map<String, Integer> values = new HashMap<>();
            values.put("sessions", sessions);
            values.put("playtime", hoursPlayed);
            values.put("afk_time", afkSeconds);
            values.put("time_since_death", timeSinceDeath);
            values.put("hours_per_kill", hoursPerKill);
            values.put("kd_ratio", kdRatio);
            values.put("deaths", deaths);
            values.put("mob_kills", mobKills);
            values.put("player_kills", playerKills);
            values.put("damage_dealt", damageDealt);
            values.put("damage_taken", damageTaken);
            values.put("adv_total", adv.getOrDefault("adv_total", 0));
            values.put("adv_story", adv.getOrDefault("adv_story", 0));
            values.put("adv_nether", adv.getOrDefault("adv_nether", 0));
            values.put("adv_end", adv.getOrDefault("adv_end", 0));
            values.put("adv_adventure", adv.getOrDefault("adv_adventure", 0));
            values.put("adv_husbandry", adv.getOrDefault("adv_husbandry", 0));
            values.put("distance_traveled", distanceMeters);
            values.put("blocks_mined", blocksMined);
            values.put("horse_rode", horseCm / 100);
            values.put("aviation", aviateCm / 100);
            values.put("villager_trades", villagerTrades);

            valuesByPlayer.put(player.name, values);
        }

        Bukkit.getScheduler().runTask(this, () -> applyScoreboard(valuesByPlayer));
    }

    private void applyScoreboard(Map<String, Map<String, Integer>> valuesByPlayer) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard board = manager.getMainScoreboard();
        Set<String> objectives = Set.of(
            "sessions",
            "playtime",
            "afk_time",
            "time_since_death",
            "hours_per_kill",
            "kd_ratio",
            "deaths",
            "mob_kills",
            "player_kills",
            "damage_dealt",
            "damage_taken",
            "adv_total",
            "adv_story",
            "adv_nether",
            "adv_end",
            "adv_adventure",
            "adv_husbandry",
            "distance_traveled",
            "blocks_mined",
            "horse_rode",
            "aviation",
            "villager_trades"
        );

        for (String objectiveName : objectives) {
            Objective obj = board.getObjective(objectiveName);
            if (obj == null) {
                obj = board.registerNewObjective(objectiveName, "dummy");
            }
        }

        for (Map.Entry<String, Map<String, Integer>> entry : valuesByPlayer.entrySet()) {
            String playerName = entry.getKey();
            Map<String, Integer> values = entry.getValue();
            for (String objectiveName : objectives) {
                Objective obj = board.getObjective(objectiveName);
                if (obj == null) {
                    continue;
                }
                Score score = obj.getScore(playerName);
                score.setScore(values.getOrDefault(objectiveName, 0));
            }
        }
    }
}
