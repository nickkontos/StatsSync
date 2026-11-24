package com.homeofcappuccino.stats;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class StatsSyncPlugin extends JavaPlugin {
    private static final String PLAN_LIBRARIES_DIR = "/data/plugins/Plan/libraries";
    private static final String WHITELIST_PATH = "/data/whitelist.json";
    private static final String WORLD_STATS_DIR = "/data/world/players/stats";
    private static final String WORLD_ADV_DIR = "/data/world/players/advancements";
    private static final String PLAYER_STATS_OUT = "/data/player-stats/data.json";
    private static final String SERVER_INFO_OUT = "/data/server-info/data.json";
    private static final String ESSENTIALS_USERDATA_DIR = "/data/plugins/Essentials/userdata";

    private volatile boolean driverLoaded = false;

    private static final String[] ADV_KEYS = new String[]{
        "adv_story", "adv_nether", "adv_end", "adv_adventure", "adv_husbandry"
    };

    private static final Map<String, String> ADV_PREFIXES = new LinkedHashMap<>();
    static {
        ADV_PREFIXES.put("adv_story", "minecraft:story/");
        ADV_PREFIXES.put("adv_nether", "minecraft:nether/");
        ADV_PREFIXES.put("adv_end", "minecraft:end/");
        ADV_PREFIXES.put("adv_adventure", "minecraft:adventure/");
        ADV_PREFIXES.put("adv_husbandry", "minecraft:husbandry/");
    }

    private static final String[] SCORE_OBJECTIVES = new String[]{
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
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scheduleTasks();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("statssync")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /statssync reload | /statssync run <webstats|playerstats|planextensions|serverinfo>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            reloadConfig();
            Bukkit.getScheduler().cancelTasks(this);
            scheduleTasks();
            sender.sendMessage("StatsSync reloaded.");
            return true;
        }
        if (!sub.equals("run") || args.length < 2) {
            sender.sendMessage("Usage: /statssync run <webstats|playerstats|planextensions|serverinfo>");
            return true;
        }
        String target = args[1].toLowerCase(Locale.ROOT);
        Runnable task;
        switch (target) {
            case "webstats":
                task = () -> runWebstats(sender);
                break;
            case "playerstats":
                task = () -> runPlayerStats(sender);
                break;
            case "planextensions":
                task = () -> runPlanExtensions(sender);
                break;
            case "serverinfo":
                task = () -> runServerInfo(sender);
                break;
            default:
                sender.sendMessage("Unknown target: " + target);
                return true;
        }
        runAsync(task);
        return true;
    }

    private void scheduleTasks() {
        FileConfiguration cfg = getConfig();
        scheduleIfEnabled(cfg, "webstats", () -> runWebstats(null));
        scheduleIfEnabled(cfg, "playerStats", () -> runPlayerStats(null));
        scheduleIfEnabled(cfg, "planExtensions", () -> runPlanExtensions(null));
        scheduleIfEnabled(cfg, "serverInfo", () -> runServerInfo(null));
    }

    private void scheduleIfEnabled(FileConfiguration cfg, String key, Runnable task) {
        String base = key;
        boolean enabled = cfg.getBoolean(base + ".enabled", false);
        if (!enabled) return;
        int intervalMinutes = cfg.getInt(base + ".intervalMinutes", 15);
        int initialDelaySeconds = cfg.getInt(base + ".initialDelaySeconds", 60);
        long delay = initialDelaySeconds * 20L;
        long period = Math.max(1, intervalMinutes) * 60L * 20L;
        runRepeating(task, delay, period);
    }

    private void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    private void runRepeating(Runnable task, long delay, long period) {
        new BukkitRunnable() {
            @Override
            public void run() {
                task.run();
            }
        }.runTaskTimerAsynchronously(this, delay, period);
    }

    private void runWebstats(CommandSender sender) {
        try {
            ensureMysqlDriver();
            Map<String, String> whitelist = loadWhitelist();
            Map<String, UserRow> users = loadUsers();
            Map<Integer, SessionAgg> sessions = loadSessionAgg();

            Map<String, Map<String, Long>> stats = new HashMap<>();
            for (Map.Entry<String, String> entry : whitelist.entrySet()) {
                String name = entry.getKey();
                String uuid = entry.getValue();
                StatBundle bundle = loadStats(uuid);
                stats.put(uuid, bundle.toScoreValues(name, users, sessions));
            }

            applyScoreboard(stats, whitelist);
            if (sender != null) sender.sendMessage("StatsSync webstats updated.");
        } catch (Exception e) {
            getLogger().warning("webstats failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applyScoreboard(Map<String, Map<String, Long>> stats, Map<String, String> whitelist) {
        Bukkit.getScheduler().runTask(this, () -> {
            ScoreboardManager mgr = Bukkit.getScoreboardManager();
            if (mgr == null) return;
            Scoreboard board = mgr.getMainScoreboard();

            for (String objName : SCORE_OBJECTIVES) {
                Objective obj = board.getObjective(objName);
                if (obj == null) {
                    obj = board.registerNewObjective(objName, "dummy");
                }
            }

            for (Map.Entry<String, String> entry : whitelist.entrySet()) {
                String name = entry.getKey();
                String uuid = entry.getValue();
                Map<String, Long> values = stats.get(uuid);
                if (values == null) continue;
                for (String objName : SCORE_OBJECTIVES) {
                    Objective obj = board.getObjective(objName);
                    if (obj == null) continue;
                    long v = values.getOrDefault(objName, 0L);
                    Score score = obj.getScore(name);
                    score.setScore((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v)));
                }
            }
        });
    }

    private void runServerInfo(CommandSender sender) {
        try {
            ensureMysqlDriver();
            Map<String, String> props = readServerProperties();
            World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            long worldTime = world != null ? world.getTime() : 0L;
            String timeOfDay = getTimeOfDay(worldTime);

            Map<String, Long> lastSeen = loadLastSeenByUuid();
            Map<String, Long> registered = loadRegistered();
            Map<String, String> whitelist = loadWhitelist();
            Map<String, String> nicknames = loadNicknames();

            String ip = props.getOrDefault("server-ip", "").trim();
            if (ip.isEmpty()) {
                ip = getConfig().getString("serverInfo.ipOverride", "").trim();
            }
            if (ip.isEmpty()) {
                ip = fetchPublicIp();
            }

            String port = resolvePublicPort(props);

            String allowNether = props.getOrDefault("allow-nether", "").trim();
            if (allowNether.isEmpty()) allowNether = Boolean.toString(Bukkit.getAllowNether());

            String allowEnd = props.getOrDefault("allow-end", "").trim();
            if (allowEnd.isEmpty()) allowEnd = Boolean.toString(Bukkit.getAllowEnd());

            String motd = stripMotd(props.getOrDefault("motd", ""));

            String difficulty = world != null ? world.getDifficulty().name().toLowerCase(Locale.ROOT) : "";
            if (difficulty.isEmpty()) {
                difficulty = props.getOrDefault("difficulty", "").trim();
            }

            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            List<Player> spectators = new ArrayList<>();
            for (Player p : onlinePlayers) {
                if (p.getGameMode() == GameMode.SPECTATOR) spectators.add(p);
            }

            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();

            long nowMs = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"timestamp\":").append(nowMs)
              .append(",\"server\":{")
              .append("\"name\":\"").append(escape(Bukkit.getServer().getName())).append("\"")
              .append(",\"motd\":\"").append(escape(motd)).append("\"")
              .append(",\"ip\":\"").append(escape(ip)).append("\"")
              .append(",\"port\":\"").append(escape(port)).append("\"")
              .append(",\"mc_version\":\"").append(escape(Bukkit.getVersion())).append("\"")
              .append(",\"bukkit_version\":\"").append(escape(Bukkit.getBukkitVersion())).append("\"")
              .append(",\"world_type\":\"").append(escape(props.getOrDefault("level-type", ""))).append("\"")
              .append(",\"game_mode\":\"").append(escape(props.getOrDefault("gamemode", ""))).append("\"")
              .append(",\"whitelisted\":\"").append(escape(props.getOrDefault("white-list", ""))).append("\"")
              .append(",\"allow_nether\":\"").append(escape(allowNether)).append("\"")
              .append(",\"allow_end\":\"").append(escape(allowEnd)).append("\"")
              .append(",\"difficulty\":\"").append(escape(difficulty)).append("\"")
              .append(",\"world_time_ticks\":").append(worldTime)
              .append(",\"max_players\":").append(Bukkit.getMaxPlayers())
              .append("}");

            sb.append(",\"online\":{")
              .append("\"count\":").append(onlinePlayers.size())
              .append(",\"players\":[");
            boolean first = true;
            for (Player p : onlinePlayers) {
                if (!first) sb.append(',');
                first = false;
                String nickname = nicknames.getOrDefault(p.getUniqueId().toString(), "");
                sb.append("{\"name\":\"").append(escape(p.getName())).append("\"")
                  .append(",\"nickname\":\"").append(escape(nickname)).append("\"")
                  .append(",\"uuid\":\"").append(p.getUniqueId()).append("\"")
                  .append(",\"ping\":").append(p.getPing())
                  .append(",\"world\":\"").append(escape(p.getWorld().getName())).append("\"")
                  .append(",\"gamemode\":\"").append(escape(p.getGameMode().name())).append("\"}");
            }
            sb.append("]}");

            sb.append(",\"whitelist\":[");
            first = true;
            for (Map.Entry<String, String> entry : whitelist.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                String name = entry.getKey();
                String uuid = entry.getValue();
                String nickname = nicknames.getOrDefault(uuid, "");
                long seen = lastSeen.getOrDefault(uuid, 0L);
                long reg = registered.getOrDefault(uuid, 0L);
                sb.append("{\"name\":\"").append(escape(name)).append("\"")
                  .append(",\"nickname\":\"").append(escape(nickname)).append("\"")
                  .append(",\"uuid\":\"").append(escape(uuid)).append("\"")
                  .append(",\"last_seen\":").append(seen)
                  .append(",\"last_seen_f\":\"").append(escape(formatTimestamp(seen))).append("\"")
                  .append(",\"registered\":").append(reg)
                  .append(",\"registered_f\":\"").append(escape(formatTimestamp(reg))).append("\"}");
            }
            sb.append("]");

            sb.append(",\"plugins\":[");
            first = true;
            for (Plugin pl : plugins) {
                if (!first) sb.append(',');
                first = false;
                PluginDescriptionFile d = pl.getDescription();
                sb.append("{\"name\":\"").append(escape(pl.getName())).append("\"")
                  .append(",\"version\":\"").append(escape(d.getVersion())).append("\"")
                  .append(",\"authors\":\"").append(escape(String.join(", ", d.getAuthors()))).append("\"")
                  .append(",\"description\":\"").append(escape(d.getDescription())).append("\"}");
            }
            sb.append("]");

            sb.append(",\"spectator\":[");
            first = true;
            for (Player p : spectators) {
                if (!first) sb.append(',');
                first = false;
                String nickname = nicknames.getOrDefault(p.getUniqueId().toString(), "");
                sb.append("{\"name\":\"").append(escape(p.getName())).append("\"")
                  .append(",\"nickname\":\"").append(escape(nickname)).append("\"")
                  .append(",\"uuid\":\"").append(p.getUniqueId()).append("\"}");
            }
            sb.append("]}");

            writeFile(getPath("serverInfo", SERVER_INFO_OUT), sb.toString());
            if (sender != null) sender.sendMessage("StatsSync serverinfo updated.");
        } catch (Exception e) {
            getLogger().warning("serverinfo failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<String, String> readServerProperties() throws IOException {
        Path path = Path.of(getPath("serverProperties", "/data/server.properties"));
        Map<String, String> props = new HashMap<>();
        if (!Files.exists(path)) return props;
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int idx = line.indexOf('=');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim();
            String val = line.substring(idx + 1).trim();
            props.put(key, val);
        }
        return props;
    }

    private String getTimeOfDay(long worldTime) {
        long time = worldTime % 24000L;
        if (time >= 0 && time < 12300) return "Day";
        if (time < 13000) return "Sunset";
        if (time < 23000) return "Night";
        return "Sunrise";
    }

    private String stripMotd(String motd) {
        if (motd == null) return "";
        return motd.replaceAll("(?i)§[0-9A-FK-OR]", "");
    }

    private Map<String, String> loadNicknames() {
        Map<String, String> out = new HashMap<>();
        File dir = new File(getPath("essentials", ESSENTIALS_USERDATA_DIR));
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return out;
        for (File file : files) {
            String uuid = file.getName().replace(".yml", "");
            String nickname = "";
            try {
                for (String line : Files.readAllLines(file.toPath())) {
                    if (!line.startsWith("nickname:")) continue;
                    nickname = line.substring("nickname:".length()).trim();
                    nickname = nickname.replaceAll("^\"|\"$", "");
                    nickname = nickname.replaceAll("^'|'$", "");
                    nickname = stripMotd(nickname);
                    break;
                }
            } catch (IOException e) {
                continue;
            }
            if (!nickname.isBlank()) {
                out.put(uuid, nickname);
            }
        }
        return out;
    }

    private String resolvePublicPort(Map<String, String> props) {
        String override = getConfig().getString("serverInfo.portOverride", "").trim();
        if (!override.isEmpty()) return override;

        String fromCompose = parseComposePort();
        if (!fromCompose.isEmpty()) return fromCompose;

        return props.getOrDefault("server-port", "").trim();
    }

    private String parseComposePort() {
        try {
            Path path = Path.of("/data/docker-compose.yml");
            if (!Files.exists(path)) return "";
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("-")) continue;
                String entry = trimmed.substring(1).trim();
                if (entry.startsWith("\"") && entry.endsWith("\"")) {
                    entry = entry.substring(1, entry.length() - 1);
                }
                if (!entry.contains(":")) continue;
                String[] parts = entry.split(":");
                if (parts.length < 2) continue;
                String hostPort = parts[0].replaceAll("\"", "").trim();
                String containerPort = parts[1].replaceAll("\"", "").trim();
                if (containerPort.equals("25565")) return hostPort;
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }

    private String fetchPublicIp() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.ipify.org");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String ip = reader.readLine();
                return ip == null ? "" : ip.trim();
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String formatTimestamp(long ms) {
        if (ms <= 0) return "Never";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return fmt.format(new java.util.Date(ms));
    }

    private Map<String, Long> loadLastSeenByUuid() {
        Map<String, Long> out = new HashMap<>();
        String dbUrl = getDbUrl();
        String sql = "SELECT u.uuid, MAX(GREATEST(s.session_start, s.session_end)) FROM plan_users u " +
                     "LEFT JOIN plan_sessions s ON s.user_id=u.id GROUP BY u.uuid";
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String uuid = rs.getString(1);
                long last = rs.getLong(2);
                out.put(uuid, last);
            }
        } catch (SQLException e) {
            getLogger().warning("loadLastSeenByUuid failed: " + e.getMessage());
        }
        return out;
    }

    private void runPlayerStats(CommandSender sender) {
        try {
            ensureMysqlDriver();
            Map<String, UserRow> users = loadUsers();
            Map<Integer, SessionAgg> sessions = loadSessionAgg();
            Map<String, Long> registered = loadRegistered();

            List<Descriptor> descriptors = loadDescriptors();
            Map<String, Map<String, Long>> extValues = computeExtensionValues(users.keySet(), sessions);

            String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new java.util.Date());
            long nowMs = System.currentTimeMillis();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"timestamp\":").append(nowMs)
              .append(",\"timestamp_f\":\"").append(escape(now)).append("\"")
              .append(",\"players\":[");

            boolean firstPlayer = true;
            for (Map.Entry<String, UserRow> entry : users.entrySet()) {
                String uuid = entry.getKey();
                UserRow row = entry.getValue();
                SessionAgg agg = sessions.getOrDefault(row.id, new SessionAgg());

                if (!firstPlayer) sb.append(',');
                firstPlayer = false;

                sb.append("{\"playerUUID\":\"").append(escape(uuid)).append("\"")
                  .append(",\"playerName\":\"").append(escape(row.name)).append("\"")
                  .append(",\"activityIndex\":0")
                  .append(",\"playtimeActive\":").append(agg.activePlaytime)
                  .append(",\"sessionCount\":").append(agg.sessions)
                  .append(",\"lastSeen\":").append(agg.lastSeen)
                  .append(",\"registered\":").append(registered.getOrDefault(uuid, 0L))
                  .append(",\"country\":\"\"")
                  .append(",\"pingAverage\":0")
                  .append(",\"pingMax\":0")
                  .append(",\"pingMin\":0")
                  .append(",\"extensionValues\":{");

                Map<String, Long> vals = extValues.getOrDefault(uuid, Collections.emptyMap());
                boolean firstVal = true;
                for (Descriptor d : descriptors) {
                    if (!firstVal) sb.append(',');
                    firstVal = false;
                    long val = vals.getOrDefault(d.name, 0L);
                    sb.append("\"").append(escape(d.name)).append("\":{")
                      .append("\"description\":{\"name\":\"").append(escape(d.name))
                      .append("\",\"text\":\"").append(escape(d.text))
                      .append("\",\"icon\":{\"family\":\"SOLID\",\"familyClass\":\"fa\",\"color\":\"NONE\",\"colorClass\":\"\",\"iconName\":\"").append(escape(d.icon)).append("\"},\"priority\":0}")
                      .append(",\"type\":\"NUMBER\",\"value\":\"").append(val).append("\"}");
                }
                sb.append("}}");
            }

            sb.append("]");
            sb.append(",\"extensionDescriptors\":[");
            boolean firstDesc = true;
            for (Descriptor d : descriptors) {
                if (!firstDesc) sb.append(',');
                firstDesc = false;
                sb.append("{\"name\":\"").append(escape(d.name)).append("\"")
                  .append(",\"text\":\"").append(escape(d.text)).append("\"")
                  .append(",\"icon\":{\"family\":\"SOLID\",\"familyClass\":\"fa\",\"color\":\"NONE\",\"colorClass\":\"\",\"iconName\":\"").append(escape(d.icon)).append("\"},\"priority\":0}");
            }
            sb.append("]}");

            writeFile(getPath("playerStats", PLAYER_STATS_OUT), sb.toString());
            if (sender != null) sender.sendMessage("StatsSync playerstats updated.");
        } catch (Exception e) {
            getLogger().warning("playerstats failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void runPlanExtensions(CommandSender sender) {
        try {
            ensureMysqlDriver();
            Map<String, UserRow> users = loadUsers();
            Map<Integer, SessionAgg> sessions = loadSessionAgg();
            Map<String, Integer> providers = loadProviderIds();
            Map<String, Map<String, Long>> values = computeExtensionValues(users.keySet(), sessions);

            List<String> names = new ArrayList<>(providers.keySet());
            if (names.isEmpty()) return;

            String dbUrl = getDbUrl();
            try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass())) {
                conn.setAutoCommit(false);
                String inClause = String.join(",", Collections.nCopies(names.size(), "?"));
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM plan_extension_user_values WHERE provider_id IN (" + inClause + ")")) {
                    int idx = 1;
                    for (String name : names) del.setInt(idx++, providers.get(name));
                    del.executeUpdate();
                }

                String insertSql = "INSERT INTO plan_extension_user_values " +
                        "(boolean_value,double_value,percentage_value,long_value,string_value,component_value,group_value,uuid,provider_id) " +
                        "VALUES (NULL,NULL,NULL,?,NULL,NULL,NULL,?,?)";
                try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                    for (Map.Entry<String, Map<String, Long>> entry : values.entrySet()) {
                        String uuid = entry.getKey();
                        Map<String, Long> vals = entry.getValue();
                        for (String name : names) {
                            long v = vals.getOrDefault(name, 0L);
                            ins.setLong(1, v);
                            ins.setString(2, uuid);
                            ins.setInt(3, providers.get(name));
                            ins.addBatch();
                        }
                    }
                    ins.executeBatch();
                }

                try (PreparedStatement up = conn.prepareStatement("UPDATE plan_extension_plugins SET last_updated=?")) {
                    up.setLong(1, System.currentTimeMillis());
                    up.executeUpdate();
                }
                conn.commit();
            }
            if (sender != null) sender.sendMessage("StatsSync planextensions updated.");
        } catch (Exception e) {
            getLogger().warning("planextensions failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<String, Map<String, Long>> computeExtensionValues(Collection<String> uuids, Map<Integer, SessionAgg> sessions) throws IOException {
        Map<String, Map<String, Long>> out = new HashMap<>();
        for (String uuid : uuids) {
            StatBundle bundle = loadStats(uuid);
            Map<String, Long> values = bundle.toExtensionValues();
            // afk_time from sessions (ms)
            int userId = bundle.userId;
            if (userId > 0) {
                SessionAgg agg = sessions.getOrDefault(userId, new SessionAgg());
                values.put("afk_time", agg.afkTime);
            } else {
                values.put("afk_time", 0L);
            }
            out.put(uuid, values);
        }
        return out;
    }

    private StatBundle loadStats(String uuid) throws IOException {
        Path statsPath = Path.of(getPath("stats", WORLD_STATS_DIR), uuid + ".json");
        Map<String, Object> stats = new HashMap<>();
        Map<String, Object> custom = new HashMap<>();
        Map<String, Object> mined = new HashMap<>();
        if (Files.exists(statsPath)) {
            String raw = Files.readString(statsPath);
            Map<String, Object> root = JsonHelper.parse(raw);
            Object statsObj = root.get("stats");
            if (statsObj instanceof Map) {
                stats = (Map<String, Object>) statsObj;
            }
            Object customObj = stats.get("minecraft:custom");
            if (customObj instanceof Map) custom = (Map<String, Object>) customObj;
            Object minedObj = stats.get("minecraft:mined");
            if (minedObj instanceof Map) mined = (Map<String, Object>) minedObj;
        }

        Map<String, Object> adv = new HashMap<>();
        Path advPath = Path.of(getPath("advancements", WORLD_ADV_DIR), uuid + ".json");
        if (Files.exists(advPath)) {
            String raw = Files.readString(advPath);
            adv = JsonHelper.parse(raw);
        }

        return StatBundle.from(uuid, stats, custom, mined, adv);
    }

    private Map<String, UserRow> loadUsers() {
        Map<String, UserRow> users = new LinkedHashMap<>();
        String dbUrl = getDbUrl();
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, uuid, name FROM plan_users ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt(1);
                String uuid = rs.getString(2);
                String name = rs.getString(3);
                users.put(uuid, new UserRow(id, uuid, name));
            }
        } catch (SQLException e) {
            getLogger().warning("loadUsers failed: " + e.getMessage());
        }
        return users;
    }

    private Map<Integer, SessionAgg> loadSessionAgg() {
        Map<Integer, SessionAgg> out = new HashMap<>();
        String dbUrl = getDbUrl();
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id, session_start, session_end, afk_time FROM plan_sessions")) {
            while (rs.next()) {
                int userId = rs.getInt(1);
                long start = rs.getLong(2);
                long end = rs.getLong(3);
                long afk = rs.getLong(4);
                SessionAgg agg = out.computeIfAbsent(userId, k -> new SessionAgg());
                agg.sessions += 1;
                agg.afkTime += Math.max(0, afk);
                long last = Math.max(start, end);
                if (last > agg.lastSeen) agg.lastSeen = last;
                if (end > 0 && end >= start) {
                    long active = (end - start) - afk;
                    if (active < 0) active = 0;
                    agg.activePlaytime += active;
                }
            }
        } catch (SQLException e) {
            getLogger().warning("loadSessionAgg failed: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Long> loadRegistered() {
        Map<String, Long> out = new HashMap<>();
        String dbUrl = getDbUrl();
        String sql = "SELECT u.uuid, i.registered FROM plan_user_info i JOIN plan_users u ON u.id=i.user_id";
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            getLogger().warning("loadRegistered failed: " + e.getMessage());
        }
        return out;
    }

    private List<Descriptor> loadDescriptors() {
        List<Descriptor> out = new ArrayList<>();
        Map<Integer, String> icons = new HashMap<>();
        String dbUrl = getDbUrl();
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM plan_extension_icons")) {
            while (rs.next()) {
                icons.put(rs.getInt(1), rs.getString(2));
            }
        } catch (SQLException e) {
            getLogger().warning("loadDescriptors icons failed: " + e.getMessage());
        }

        String sql = "SELECT id, name, text, icon_id FROM plan_extension_providers WHERE show_in_players_table=1 AND hidden=0 ORDER BY id";
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(2);
                String text = rs.getString(3);
                String icon = icons.getOrDefault(rs.getInt(4), "chart");
                out.add(new Descriptor(name, text, icon));
            }
        } catch (SQLException e) {
            getLogger().warning("loadDescriptors failed: " + e.getMessage());
        }
        return out;
    }

    private Map<String, Integer> loadProviderIds() {
        Map<String, Integer> out = new LinkedHashMap<>();
        String dbUrl = getDbUrl();
        String sql = "SELECT id, name FROM plan_extension_providers";
        try (Connection conn = DriverManager.getConnection(dbUrl, getDbUser(), getDbPass());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(2), rs.getInt(1));
            }
        } catch (SQLException e) {
            getLogger().warning("loadProviderIds failed: " + e.getMessage());
        }
        // only keep ones we manage
        Map<String, Integer> filtered = new LinkedHashMap<>();
        for (String key : new String[]{
                "deaths", "mob_kills", "player_kills", "damage_dealt", "damage_taken",
                "adv_total", "adv_story", "adv_nether", "adv_end", "adv_adventure", "adv_husbandry",
                "distance_traveled", "blocks_mined", "trades_with_villager", "time_since_death", "afk_time"
        }) {
            Integer id = out.get(key);
            if (id != null) filtered.put(key, id);
        }
        return filtered;
    }

    private Map<String, String> loadWhitelist() throws IOException {
        Path path = Path.of(getPath("whitelist", WHITELIST_PATH));
        if (!Files.exists(path)) return Collections.emptyMap();
        String raw = Files.readString(path);
        List<Map<String, Object>> list = (List<Map<String, Object>>) JsonHelper.parseList(raw);
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> seenUuids = new HashSet<>();
        for (Map<String, Object> entry : list) {
            Object name = entry.get("name");
            Object uuid = entry.get("uuid");
            if (name == null || uuid == null) continue;
            String normName = String.valueOf(name).trim();
            String normUuid = String.valueOf(uuid).trim();
            if (normName.isEmpty() || normUuid.isEmpty()) continue;
            if (seenUuids.contains(normUuid)) continue;
            seenUuids.add(normUuid);
            out.put(normName, normUuid);
        }
        return out;
    }

    private void ensureMysqlDriver() throws Exception {
        if (driverLoaded) return;
        File jar = resolveMysqlConnector();
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader());
        Class.forName("com.mysql.cj.jdbc.Driver", true, loader);
        driverLoaded = true;
    }

    private File resolveMysqlConnector() throws IOException {
        File dir = new File(getPath("planLibraries", PLAN_LIBRARIES_DIR));
        if (!dir.isDirectory()) {
            throw new IOException("Plan libraries directory not found: " + PLAN_LIBRARIES_DIR);
        }
        File[] jars = dir.listFiles((d, name) ->
            name.startsWith("com.mysql-mysql-connector-j-") && name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("MySQL connector not found under: " + PLAN_LIBRARIES_DIR);
        }
        Arrays.sort(jars, Comparator.comparing(File::getName).reversed());
        return jars[0];
    }

    private String getPath(String key, String fallback) {
        return getConfig().getString("paths." + key, fallback);
    }

    private String getDbUrl() {
        FileConfiguration cfg = getConfig();
        String host = cfg.getString("planDb.host", "plan-db");
        int port = cfg.getInt("planDb.port", 3306);
        String db = cfg.getString("planDb.database", "plan");
        String options = cfg.getString("planDb.options", "");
        if (options != null && !options.isEmpty() && !options.startsWith("?")) {
            options = "?" + options;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + db + (options == null ? "" : options);
    }

    private String getDbUser() {
        return getConfig().getString("planDb.user", "plan");
    }

    private String getDbPass() {
        return getConfig().getString("planDb.password", "");
    }

    private void writeFile(String path, String content) throws IOException {
        Path p = Path.of(path);
        Files.createDirectories(p.getParent());
        try (FileWriter fw = new FileWriter(p.toFile(), false)) {
            fw.write(content);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static class UserRow {
        final int id;
        final String uuid;
        final String name;
        UserRow(int id, String uuid, String name) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static class SessionAgg {
        long sessions = 0;
        long afkTime = 0;
        long activePlaytime = 0;
        long lastSeen = 0;
    }

    private static class Descriptor {
        final String name;
        final String text;
        final String icon;
        Descriptor(String name, String text, String icon) {
            this.name = name;
            this.text = text;
            this.icon = icon;
        }
    }

    private static class StatBundle {
        final String uuid;
        final Map<String, Object> custom;
        final Map<String, Object> mined;
        final Map<String, Object> adv;
        int userId;

        private StatBundle(String uuid, Map<String, Object> custom, Map<String, Object> mined, Map<String, Object> adv) {
            this.uuid = uuid;
            this.custom = custom;
            this.mined = mined;
            this.adv = adv;
        }

        static StatBundle from(String uuid, Map<String, Object> stats, Map<String, Object> custom, Map<String, Object> mined, Map<String, Object> adv) {
            return new StatBundle(uuid, custom, mined, adv);
        }

        Map<String, Long> toExtensionValues() {
            Map<String, Long> values = new HashMap<>();
            long deaths = getLong(custom, "minecraft:deaths");
            long mob_kills = getLong(custom, "minecraft:mob_kills");
            long player_kills = getLong(custom, "minecraft:player_kills");
            long damage_dealt = getLong(custom, "minecraft:damage_dealt");
            long damage_taken = getLong(custom, "minecraft:damage_taken");
            long time_since_death = getLong(custom, "minecraft:time_since_death");
            long trades_with_villager = getLong(custom, "minecraft:traded_with_villager");
            long blocks_mined = sumLongs(mined);
            long distance_traveled = sumDistance(custom);

            Map<String, Long> advCounts = countAdvancements(adv);
            values.put("deaths", deaths);
            values.put("mob_kills", mob_kills);
            values.put("player_kills", player_kills);
            values.put("damage_dealt", damage_dealt);
            values.put("damage_taken", damage_taken);
            values.put("adv_total", advCounts.getOrDefault("adv_total", 0L));
            values.put("adv_story", advCounts.getOrDefault("adv_story", 0L));
            values.put("adv_nether", advCounts.getOrDefault("adv_nether", 0L));
            values.put("adv_end", advCounts.getOrDefault("adv_end", 0L));
            values.put("adv_adventure", advCounts.getOrDefault("adv_adventure", 0L));
            values.put("adv_husbandry", advCounts.getOrDefault("adv_husbandry", 0L));
            values.put("distance_traveled", distance_traveled);
            values.put("blocks_mined", blocks_mined);
            values.put("trades_with_villager", trades_with_villager);
            values.put("time_since_death", time_since_death);
            return values;
        }

        Map<String, Long> toScoreValues(String name, Map<String, UserRow> users, Map<Integer, SessionAgg> sessions) {
            UserRow row = users.get(uuid);
            if (row != null) userId = row.id;
            SessionAgg agg = row == null ? new SessionAgg() : sessions.getOrDefault(row.id, new SessionAgg());

            Map<String, Long> values = new HashMap<>(toExtensionValues());
            long totalKills = values.getOrDefault("mob_kills", 0L) + values.getOrDefault("player_kills", 0L);
            long deaths = values.getOrDefault("deaths", 0L);
            long playTimeTicks = getLong(custom, "minecraft:play_time");
            long hoursPlayed = (playTimeTicks / 20L) / 3600L;

            long kd = (deaths > 0) ? (long) Math.round((totalKills * 100.0) / deaths) : (totalKills * 100);
            long hpk = (totalKills > 0) ? (long) Math.round((hoursPlayed * 100.0) / totalKills) : 0;

            long horse_cm = getLong(custom, "minecraft:horse_one_cm");
            long aviate_cm = getLong(custom, "minecraft:aviate_one_cm");

            values.put("sessions", agg.sessions);
            values.put("playtime", hoursPlayed);
            values.put("afk_time", agg.afkTime / 1000L);
            values.put("hours_per_kill", hpk);
            values.put("kd_ratio", kd);
            values.put("distance_traveled", values.getOrDefault("distance_traveled", 0L) / 100L);
            values.put("horse_rode", horse_cm / 100L);
            values.put("aviation", aviate_cm / 100L);
            values.put("villager_trades", values.getOrDefault("trades_with_villager", 0L));
            return values;
        }

        private static long sumDistance(Map<String, Object> custom) {
            long total = 0;
            for (Map.Entry<String, Object> e : custom.entrySet()) {
                String key = e.getKey();
                if (key != null && key.endsWith("_one_cm")) {
                    total += toLong(e.getValue());
                }
            }
            return total;
        }

        private static long sumLongs(Map<String, Object> map) {
            long total = 0;
            for (Object v : map.values()) total += toLong(v);
            return total;
        }

        private static long getLong(Map<String, Object> map, String key) {
            return toLong(map.get(key));
        }

        private static long toLong(Object v) {
            if (v == null) return 0;
            if (v instanceof Number) return ((Number) v).longValue();
            try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
        }

        private static Map<String, Long> countAdvancements(Map<String, Object> adv) {
            long total = 0;
            Map<String, Long> per = new HashMap<>();
            for (String k : ADV_KEYS) per.put(k, 0L);
            for (Map.Entry<String, Object> entry : adv.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("minecraft:recipes/")) continue;
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> val = (Map<String, Object>) entry.getValue();
                Object doneObj = val.get("done");
                boolean done = Boolean.TRUE.equals(doneObj) || "true".equals(String.valueOf(doneObj));
                if (!done) continue;
                total++;
                for (Map.Entry<String, String> prefix : ADV_PREFIXES.entrySet()) {
                    if (key.startsWith(prefix.getValue())) {
                        per.put(prefix.getKey(), per.get(prefix.getKey()) + 1);
                        break;
                    }
                }
            }
            per.put("adv_total", total);
            return per;
        }
    }

    // Reads the stats and advancement files written by Minecraft
    private static class JsonHelper {
        static Map<String, Object> parse(String raw) throws IOException {
            return (Map<String, Object>) parseValue(new Tokenizer(raw));
        }

        static List<?> parseList(String raw) throws IOException {
            return (List<?>) parseValue(new Tokenizer(raw));
        }

        private static Object parseValue(Tokenizer t) throws IOException {
            t.skipWhitespace();
            char c = t.peek();
            if (c == '{') return parseObject(t);
            if (c == '[') return parseArray(t);
            if (c == '"') return t.readString();
            if (c == 't' || c == 'f') return t.readBoolean();
            if (c == 'n') { t.readNull(); return null; }
            return t.readNumber();
        }

        private static Map<String, Object> parseObject(Tokenizer t) throws IOException {
            Map<String, Object> obj = new LinkedHashMap<>();
            t.expect('{');
            t.skipWhitespace();
            if (t.peek() == '}') { t.expect('}'); return obj; }
            while (true) {
                t.skipWhitespace();
                String key = t.readString();
                t.skipWhitespace();
                t.expect(':');
                Object val = parseValue(t);
                obj.put(key, val);
                t.skipWhitespace();
                char c = t.read();
                if (c == '}') break;
                if (c != ',') throw new IOException("Expected ',' or '}'");
            }
            return obj;
        }

        private static List<Object> parseArray(Tokenizer t) throws IOException {
            List<Object> arr = new ArrayList<>();
            t.expect('[');
            t.skipWhitespace();
            if (t.peek() == ']') { t.expect(']'); return arr; }
            while (true) {
                Object val = parseValue(t);
                arr.add(val);
                t.skipWhitespace();
                char c = t.read();
                if (c == ']') break;
                if (c != ',') throw new IOException("Expected ',' or ']'" );
            }
            return arr;
        }

        private static class Tokenizer {
            private final String s;
            private int i = 0;
            Tokenizer(String s) { this.s = s; }
            void skipWhitespace() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
            char peek() throws IOException { if (i >= s.length()) throw new IOException("EOF"); return s.charAt(i); }
            char read() throws IOException { if (i >= s.length()) throw new IOException("EOF"); return s.charAt(i++); }
            void expect(char c) throws IOException { if (read() != c) throw new IOException("Expected " + c); }
            String readString() throws IOException {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = read();
                    if (c == '"') break;
                    if (c == '\\') {
                        char n = read();
                        switch (n) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                String hex = s.substring(i, i + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                                break;
                            default: sb.append(n);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
            Boolean readBoolean() throws IOException {
                if (s.startsWith("true", i)) { i += 4; return true; }
                if (s.startsWith("false", i)) { i += 5; return false; }
                throw new IOException("Invalid boolean");
            }
            void readNull() throws IOException {
                if (!s.startsWith("null", i)) throw new IOException("Invalid null");
                i += 4;
            }
            Number readNumber() throws IOException {
                int start = i;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                        i++;
                    } else break;
                }
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    try { return Double.parseDouble(num); } catch (Exception e) { return 0; }
                }
                try { return Long.parseLong(num); } catch (Exception e) { return 0; }
            }
        }
    }
}
