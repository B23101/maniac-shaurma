package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.libs.snakeyaml.DumperOptions;
import dev.shaurmalib.libs.snakeyaml.Yaml;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent map markup in one points.yml resource.
 *
 * The legacy points/*.yml files are read once when points.yml has no
 * canonical point sections, then merged into the unified resource.
 */
public final class MapPointConfigs {

    public enum Type {
        SURVIVOR_SPAWNS("survivorSpawns", "survivor_spawns.yml"),
        MANIAC_SPAWNS("maniacSpawns", "maniac_spawns.yml"),
        ITEM_POINTS("itemPoints", "item_points.yml"),
        GENERATOR_POINTS("generatorPoints", "generator_points.yml"),
        EXIT_POINTS("exitPoints", "exit_points.yml");

        private final String key;
        private final String legacyFileName;

        Type(String key, String legacyFileName) {
            this.key = key;
            this.legacyFileName = legacyFileName;
        }
    }

    public record PointData(BlockPos pos, float yaw, String ownerId) {}

    public record Snapshot(List<PointData> survivorSpawns, List<PointData> maniacSpawns,
                           List<PointData> itemPoints, List<PointData> generatorPoints,
                           List<PointData> exitPoints,
                           double lobbyX, double lobbyY, double lobbyZ, float lobbyYaw) {}

    private static final Yaml YAML = buildYaml();
    private static volatile Path file;
    private static volatile Path legacyDirectory;
    private static volatile Snapshot snapshot = empty();
    private static volatile long fileMtime = -1;
    private static final Map<Type, Long> legacyMtimes = new LinkedHashMap<>();

    private MapPointConfigs() {}

    public static void init(Path namespaceDir) {
        file = namespaceDir.resolve("points.yml");
        legacyDirectory = namespaceDir.resolve("points");
        reload();
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static void reload() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            ManiacMod.LOGGER.error("[config] не вдалось створити теку точок: {}", e.getMessage());
            return;
        }

        Map<String, Object> root = read(file);
        migrateLegacyIfNeeded(root);
        double[] lobby = readLobby(root);
        snapshot = new Snapshot(
            readPoints(root, Type.SURVIVOR_SPAWNS),
            readPoints(root, Type.MANIAC_SPAWNS),
            readPoints(root, Type.ITEM_POINTS),
            readPoints(root, Type.GENERATOR_POINTS),
            readPoints(root, Type.EXIT_POINTS),
            lobby[0], lobby[1], lobby[2], (float) lobby[3]);
        fileMtime = mtime(file);
        synchronized (legacyMtimes) {
            for (Type type : Type.values()) legacyMtimes.put(type, mtime(legacyFile(type)));
        }
    }

    public static boolean tickWatcher() {
        if (file == null) return false;
        boolean changed = mtime(file) != fileMtime;
        synchronized (legacyMtimes) {
            for (Type type : Type.values()) {
                if (mtime(legacyFile(type)) != legacyMtimes.getOrDefault(type, -1L)) changed = true;
            }
        }
        if (!changed) return false;
        reload();
        return true;
    }

    public static void add(Type type, PointData point) {
        List<PointData> points = new ArrayList<>(points(type));
        points.add(point);
        writePoints(type, points);
        reload();
    }

    public static boolean remove(Type type, int index) {
        List<PointData> points = new ArrayList<>(points(type));
        if (index < 0 || index >= points.size()) return false;
        points.remove(index);
        writePoints(type, points);
        reload();
        return true;
    }

    public static void clearAll() {
        if (file == null) return;
        Map<String, Object> root = read(file);
        for (Type type : Type.values()) root.put(type.key, List.of());
        root.put("lobby", lobbyMap(0, 64, 0, 0));
        write(file, root);
        reload();
    }

    public static List<PointData> points(Type type) {
        Snapshot current = snapshot;
        return switch (type) {
            case SURVIVOR_SPAWNS -> current.survivorSpawns();
            case MANIAC_SPAWNS -> current.maniacSpawns();
            case ITEM_POINTS -> current.itemPoints();
            case GENERATOR_POINTS -> current.generatorPoints();
            case EXIT_POINTS -> current.exitPoints();
        };
    }

    public static void writeLobby(double x, double y, double z, float yaw) {
        if (file == null) return;
        Map<String, Object> root = read(file);
        root.put("lobby", lobbyMap(x, y, z, yaw));
        write(file, root);
    }

    private static double[] readLobby(Map<String, Object> root) {
        Object raw = root.get("lobby");
        if (!(raw instanceof Map<?, ?> map)) {
            Map<String, Object> updated = new LinkedHashMap<>(root);
            updated.put("lobby", lobbyMap(0, 64, 0, 0));
            write(file, updated);
            return new double[] {0, 64, 0, 0};
        }
        return new double[] {number(map.get("x"), 0), number(map.get("y"), 64),
            number(map.get("z"), 0), number(map.get("yaw"), 0)};
    }

    private static List<PointData> readPoints(Map<String, Object> root, Type type) {
        Object raw = root.get(type.key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<PointData> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            result.add(new PointData(
                new BlockPos((int) number(map.get("x"), 0), (int) number(map.get("y"), 64),
                    (int) number(map.get("z"), 0)),
                (float) number(map.get("yaw"), 0),
                text(map.get("owner"))));
        }
        return List.copyOf(result);
    }

    private static void writePoints(Type type, List<PointData> points) {
        if (file == null) return;
        List<Map<String, Object>> values = new ArrayList<>();
        for (PointData point : points) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("x", point.pos().getX());
            value.put("y", point.pos().getY());
            value.put("z", point.pos().getZ());
            value.put("yaw", point.yaw());
            if (point.ownerId() != null && !point.ownerId().isBlank()) {
                value.put("owner", point.ownerId());
            }
            values.add(value);
        }
        Map<String, Object> root = read(file);
        root.put(type.key, values);
        write(file, root);
    }

    private static void migrateLegacyIfNeeded(Map<String, Object> root) {
        boolean hasCanonicalPoints = false;
        for (Type type : Type.values()) {
            if (root.containsKey(type.key)) {
                hasCanonicalPoints = true;
                break;
            }
        }
        boolean changed = false;
        if (!hasCanonicalPoints) {
            for (Type type : Type.values()) {
                Path legacy = legacyFile(type);
                if (!Files.exists(legacy)) continue;
                root.put(type.key, readLegacyPoints(legacy));
                changed = true;
            }
        }
        if (!root.containsKey("lobby")) {
            Path legacyLobby = legacyDirectory.resolve("lobby.yml");
            if (Files.exists(legacyLobby)) {
                Map<String, Object> lobbyRoot = read(legacyLobby);
                Object lobby = lobbyRoot.get("lobby");
                if (lobby instanceof Map<?, ?>) {
                    root.put("lobby", lobby);
                    changed = true;
                }
            }
        }
        if (changed) write(file, root);
    }

    private static List<Map<String, Object>> readLegacyPoints(Path legacy) {
        Object raw = read(legacy).get("points");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add(castMap(map));
        }
        return result;
    }

    private static Map<String, Object> lobbyMap(double x, double y, double z, float yaw) {
        Map<String, Object> lobby = new LinkedHashMap<>();
        lobby.put("x", x);
        lobby.put("y", y);
        lobby.put("z", z);
        lobby.put("yaw", yaw);
        return lobby;
    }

    private static Map<String, Object> read(Path path) {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            Object raw = YAML.load(Files.readString(path, StandardCharsets.UTF_8));
            return raw instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.warn("[config] {} не читається: {}", path.getFileName(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static void write(Path path, Map<String, Object> root) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, YAML.dump(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ManiacMod.LOGGER.error("[config] не вдалось записати {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private static Path legacyFile(Type type) {
        return legacyDirectory.resolve(type.legacyFileName);
    }

    private static long mtime(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Snapshot empty() {
        return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), 0, 64, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Yaml buildYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options);
    }
}
