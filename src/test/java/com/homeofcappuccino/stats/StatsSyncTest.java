package com.homeofcappuccino.stats;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class StatsSyncTest {
    private Object call(String className, String methodName, Object receiver, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = Class.forName(StatsSyncPlugin.class.getName() + "$" + className);
        Method method = type.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private Object bundle(Map<String, Object> custom, Map<String, Object> mined, Map<String, Object> advancements) throws Exception {
        return call("StatBundle", "from", null,
            new Class<?>[]{String.class, Map.class, Map.class, Map.class, Map.class},
            "test-player", Map.of(), custom, mined, advancements);
    }

    @Test
    void readsStatsJsonAndEscapedNames() throws Exception {
        Map<?, ?> data = (Map<?, ?>) call("JsonHelper", "parse", null,
            new Class<?>[]{String.class}, "{\"name\":\"Zo\\u00eb\\nTest\",\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\":2}},\"done\":true}");
        assertEquals("Zoë\nTest", data.get("name"));
        assertEquals(true, data.get("done"));
        Map<?, ?> stats = (Map<?, ?>) data.get("stats");
        assertEquals(2L, ((Map<?, ?>) stats.get("minecraft:custom")).get("minecraft:deaths"));
    }

    @Test
    void readsWhitelistArrays() throws Exception {
        List<?> entries = (List<?>) call("JsonHelper", "parseList", null,
            new Class<?>[]{String.class}, "[{\"name\":\"Alice\",\"uuid\":\"test-id\"}]");
        assertEquals(1, entries.size());
        assertEquals("Alice", ((Map<?, ?>) entries.getFirst()).get("name"));
        assertEquals(List.of(), call("JsonHelper", "parseList", null, new Class<?>[]{String.class}, "[]"));
    }

    @Test
    void convertsTickAndDistanceUnitsForScoreboards() throws Exception {
        Object stats = bundle(Map.of("minecraft:play_time", 144000L,
            "minecraft:deaths", 2L, "minecraft:mob_kills", 5L, "minecraft:player_kills", 1L,
            "minecraft:walk_one_cm", 2500L, "minecraft:horse_one_cm", 1000L,
            "minecraft:aviate_one_cm", 500L), Map.of("minecraft:stone", 15L, "minecraft:dirt", 4L), Map.of());
        Map<?, ?> scores = (Map<?, ?>) call("StatBundle", "toScoreValues", stats,
            new Class<?>[]{String.class, Map.class, Map.class}, "Alice", Map.of(), Map.of());
        assertEquals(2L, scores.get("playtime"));
        assertEquals(40L, scores.get("distance_traveled"));
        assertEquals(10L, scores.get("horse_rode"));
        assertEquals(5L, scores.get("aviation"));
        assertEquals(19L, scores.get("blocks_mined"));
        assertEquals(300L, scores.get("kd_ratio"));
        assertEquals(33L, scores.get("hours_per_kill"));
    }

    @Test
    void countsCompletedAdvancementsAndHandlesNewPlayers() throws Exception {
        Object stats = bundle(Map.of(), Map.of(), Map.of(
            "minecraft:story/root", Map.of("done", true),
            "minecraft:nether/root", Map.of("done", false),
            "minecraft:recipes/building_blocks/stone", Map.of("done", true)));
        Map<?, ?> scores = (Map<?, ?>) call("StatBundle", "toScoreValues", stats,
            new Class<?>[]{String.class, Map.class, Map.class}, "Alice", Map.of(), Map.of());
        assertEquals(1L, scores.get("adv_total"));
        assertEquals(1L, scores.get("adv_story"));
        assertEquals(0L, scores.get("kd_ratio"));
        assertEquals(0L, scores.get("hours_per_kill"));
    }

    @Test
    void shipsDisabledJobsAndOperatorCommands() {
        Yaml yaml = new Yaml();
        Map<?, ?> config = yaml.load(getClass().getResourceAsStream("/config.yml"));
        for (String job : List.of("webstats", "playerStats", "planExtensions", "serverInfo")) {
            assertEquals(false, ((Map<?, ?>) config.get(job)).get("enabled"));
        }
        assertEquals("", ((Map<?, ?>) config.get("planDb")).get("password"));
        Map<?, ?> plugin = yaml.load(getClass().getResourceAsStream("/plugin.yml"));
        Map<?, ?> commands = (Map<?, ?>) plugin.get("commands");
        assertEquals("statssync.admin", ((Map<?, ?>) commands.get("statssync")).get("permission"));
    }
}
