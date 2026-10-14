package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.map.zones.ClearingZone;
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
 * Persistent-зберігання галявин евакуації у {@code zones.yml} — {@link ConfigSchema#ZONES}
 * (DATA-блок; той самий файл, що й темні зони, але інші ключі). Наповнюється ЛИШЕ командами
 * {@code /maniac clearing ...} і {@code /maniac heli entry}; вміст ніколи не відновлюється з
 * дефолту (видалена галявина лишається видаленою).
 *
 * <p>Кожен клас читає ВЕСЬ файл, міняє лише свій ключ і пише файл назад — так
 * {@code DarkZoneConfigs} і цей клас не затирають одне одного (той самий прийом, що в
 * {@code MapPointConfigs} із лобі).</p>
 *
 * <pre>
 * clearings:
 *   - name: polyana_1
 *     corners:
 *       - {x: 100, z: -40}
 *       - {x: 130, z: -10}
 *     heli: {x: 115, y: 64, z: -25}
 * heliEntry: {x: 400, z: -25}   # лише НАПРЯМОК від галявини; дистанція появи — heliSpawnDistanceBlocks
 * </pre>
 */
public final class ClearingConfigs {

    private static final String KEY = "clearings";
    private static final String ENTRY_KEY = "heliEntry";
    private static final Yaml YAML = buildYaml();

    public enum Result { OK, NO_SUCH_ZONE, ZONE_EXISTS, CORNERS_FULL, NEED_CORNERS, HELI_OUTSIDE }

    private static volatile Path file;
    private static volatile List<ClearingZone> snapshot = List.of();
    private static volatile BlockPos entry = null;
    private static volatile long fileMtime = -1;

    private ClearingConfigs() {}

    public static void init(Path namespaceDir) {
        file = namespaceDir.resolve(ConfigSchema.ZONES.fileName());
        reload();
    }

    public static List<ClearingZone> snapshot() { return snapshot; }

    /** Лише повні галявини — ті, що реально беруть участь у матчі. */
    public static List<ClearingZone> complete() {
        List<ClearingZone> out = new ArrayList<>();
        for (ClearingZone zone : snapshot) if (zone.isComplete()) out.add(zone);
        return List.copyOf(out);
    }

    /** Точка (X/Z), у бік якої лежить місце появи вертольота; null — напрям обирається автоматично. */
    public static BlockPos entry() { return entry; }

    public static ClearingZone find(String name) {
        for (ClearingZone zone : snapshot) if (zone.name().equalsIgnoreCase(name)) return zone;
        return null;
    }

    public static void reload() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            ManiacMod.LOGGER.error("[config] не вдалось створити теку зон: {}", e.getMessage());
            return;
        }
        Map<String, Object> root = read(file);
        snapshot = readZones(root);
        entry = readEntry(root);
        fileMtime = mtime(file);
    }

    public static boolean tickWatcher() {
        if (file == null) return false;
        if (mtime(file) == fileMtime) return false;
        reload();
        return true;
    }

    // ── Команди ──────────────────────────────────────────────────────────

    public static Result create(String name) {
        if (find(name) != null) return Result.ZONE_EXISTS;
        List<ClearingZone> next = new ArrayList<>(snapshot);
        next.add(new ClearingZone(name, List.of(), null));
        writeAll(next);
        return Result.OK;
    }

    public static Result addCorner(String name, BlockPos pos) {
        ClearingZone zone = find(name);
        if (zone == null) return Result.NO_SUCH_ZONE;
        if (zone.hasBothCorners()) return Result.CORNERS_FULL;
        replace(zone, zone.withCorner(pos));
        return Result.OK;
    }

    /** Точка вертольота має бути всередині прямокутника — інакше мотузка висітиме поза галявиною. */
    public static Result setHeli(String name, BlockPos pos) {
        ClearingZone zone = find(name);
        if (zone == null) return Result.NO_SUCH_ZONE;
        if (!zone.hasBothCorners()) return Result.NEED_CORNERS;
        if (!zone.contains(pos)) return Result.HELI_OUTSIDE;
        replace(zone, zone.withHeli(pos));
        return Result.OK;
    }

    /** Скидає кути (щоб переробити зону), лишаючи ім'я. */
    public static Result resetCorners(String name) {
        ClearingZone zone = find(name);
        if (zone == null) return Result.NO_SUCH_ZONE;
        replace(zone, new ClearingZone(zone.name(), List.of(), null));
        return Result.OK;
    }

    public static boolean remove(String name) {
        List<ClearingZone> next = new ArrayList<>(snapshot);
        if (!next.removeIf(z -> z.name().equalsIgnoreCase(name))) return false;
        writeAll(next);
        return true;
    }

    public static void clearAll() {
        writeAll(List.of());
        setEntry(null);
    }

    public static void setEntry(BlockPos pos) {
        if (file == null) return;
        Map<String, Object> root = read(file);
        if (pos == null) {
            root.remove(ENTRY_KEY);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", pos.getX());
            m.put("z", pos.getZ());
            root.put(ENTRY_KEY, m);
        }
        write(file, root);
        reload();
    }

    // ── Внутрішнє ────────────────────────────────────────────────────────

    private static void replace(ClearingZone old, ClearingZone updated) {
        List<ClearingZone> next = new ArrayList<>(snapshot);
        next.replaceAll(z -> z == old ? updated : z);
        writeAll(next);
    }

    private static void writeAll(List<ClearingZone> zones) {
        if (file == null) return;
        List<Map<String, Object>> values = new ArrayList<>();
        for (ClearingZone zone : zones) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", zone.name());
            List<Map<String, Object>> corners = new ArrayList<>();
            for (BlockPos c : zone.corners()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("x", c.getX());
                cm.put("z", c.getZ());
                corners.add(cm);
            }
            m.put("corners", corners);
            if (zone.heli() != null) {
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("x", zone.heli().getX());
                hm.put("y", zone.heli().getY());
                hm.put("z", zone.heli().getZ());
                m.put("heli", hm);
            }
            values.add(m);
        }
        Map<String, Object> root = read(file);
        root.put(KEY, values);
        write(file, root);
        reload();
    }

    private static List<ClearingZone> readZones(Map<String, Object> root) {
        Object raw = root.get(KEY);
        if (!(raw instanceof List<?> list)) return List.of();
        List<ClearingZone> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String name = text(map.get("name"));
            if (name == null || name.isBlank()) continue;
            List<BlockPos> corners = new ArrayList<>();
            if (map.get("corners") instanceof List<?> cl) {
                for (Object c : cl) {
                    if (c instanceof Map<?, ?> cm) {
                        corners.add(new BlockPos((int) number(cm.get("x"), 0), 64, (int) number(cm.get("z"), 0)));
                    }
                }
            }
            BlockPos heli = null;
            if (map.get("heli") instanceof Map<?, ?> hm) {
                heli = new BlockPos((int) number(hm.get("x"), 0), (int) number(hm.get("y"), 64),
                    (int) number(hm.get("z"), 0));
            }
            result.add(new ClearingZone(name, corners, heli));
        }
        return List.copyOf(result);
    }

    private static BlockPos readEntry(Map<String, Object> root) {
        if (!(root.get(ENTRY_KEY) instanceof Map<?, ?> m)) return null;
        return new BlockPos((int) number(m.get("x"), 0), 64, (int) number(m.get("z"), 0));
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

    private static long mtime(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
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
