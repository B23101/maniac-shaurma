package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.map.zones.DarkZonePolygon;
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
 * Persistent-зберігання темних зон у {@code zones.yml} —
 * {@link ConfigSchema#ZONES} (DATA-блок, той самий файл, що й майбутні
 * інші типи зон карти). Наповнюється ЛИШЕ командами {@code /maniac
 * darkzone ...} — так само, як {@link MapPointConfigs} для точок
 * спавну: поблочне лікування, вміст ніколи не відновлюється з
 * дефолту (видалена зона лишається видаленою).
 * <p>
 * Формат у файлі — список зон під ключем {@code darkZones}, кожна зона
 * — ім'я + впорядкований список точок (без Y, як і сама
 * {@link DarkZonePolygon}):
 * <pre>
 * darkZones:
 *   - name: pidval
 *     points:
 *       - {x: 120, z: -40}
 *       - {x: 140, z: -40}
 *       - {x: 140, z: -10}
 * </pre>
 */
public final class DarkZoneConfigs {

    private static final String KEY = "darkZones";
    private static final Yaml YAML = buildYaml();

    private static volatile Path file;
    private static volatile List<DarkZonePolygon> snapshot = List.of();
    private static volatile long fileMtime = -1;

    private DarkZoneConfigs() {}

    public static void init(Path namespaceDir) {
        file = namespaceDir.resolve(ConfigSchema.ZONES.fileName());
        reload();
    }

    public static List<DarkZonePolygon> snapshot() {
        return snapshot;
    }

    public static DarkZonePolygon find(String name) {
        for (DarkZonePolygon zone : snapshot) {
            if (zone.name().equalsIgnoreCase(name)) return zone;
        }
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
        fileMtime = mtime(file);
    }

    public static boolean tickWatcher() {
        if (file == null) return false;
        if (mtime(file) == fileMtime) return false;
        reload();
        return true;
    }

    /**
     * Створює нову ПОРОЖНЮ зону (без точок) — точки додаються далі
     * {@link #addPoint}. Помилка, якщо ім'я зайняте.
     *
     * ── БАГФІКС: зона не з'являлась у файлі взагалі ───────────────────
     * Раніше тут було {@code writeAll(appendEmpty(snapshot, name))}.
     * {@link #appendEmpty} САМА вже пише порожній маркер-запис у файл і
     * викликає {@link #reload}, повертаючи новий {@code snapshot} —
     * але той снапшот НЕ містить щойно доданої зони (0 точок не
     * проходить конструктор {@link DarkZonePolygon}, {@link #readZones}
     * її відфільтровує). Далі {@link #writeAll} серіалізує РІВНО той
     * список {@code DarkZonePolygon}, який отримав, — тобто одразу
     * перезаписував файл БЕЗ щойно доданого порожнього запису,
     * стираючи те, що {@code appendEmpty} щойно записав напряму в
     * YAML. Зовні це виглядало як "команда каже 'створено', а в
     * файлі/списку зон нічого нема". {@code appendEmpty} тут — уже
     * повний ефект create(), другий {@code writeAll} був зайвим і
     * шкідливим.
     */
    public static boolean create(String name) {
        if (find(name) != null) return false;
        appendEmpty(name);
        return true;
    }

    /**
     * Додає точку (позицію гравця) в кінець зони {@code name}.
     * Повертає {@code false}, якщо зони з таким ім'ям нема.
     *
     * ── БАГФІКС: "немає такої зони" одразу після create() ────────────
     * {@link #find} дивиться лише в {@link #snapshot}, а {@link
     * #readZones} свідомо ВИКИДАЄ зі снапшоту зони з нулем точок (щойно
     * створені {@code create()} — {@link DarkZonePolygon} вимагає ≥1
     * точку і просто не може представити порожню зону). Тобто перша ж
     * точка новоствореної зони не могла додатись: {@code find(name)}
     * завжди повертав null для зони, яка щойно "успішно створилась".
     * Тому тут перевіряємо існування імені НЕ через find/snapshot, а
     * напряму в сирих (ще не відфільтрованих) записах файлу — так само,
     * як {@link #appendEmpty} їх читає для create().
     */
    public static boolean addPoint(String name, BlockPos pos) {
        if (!existsRaw(name)) return false;

        DarkZonePolygon zone = find(name);
        List<DarkZonePolygon> updated = new ArrayList<>(snapshot);
        if (zone == null) {
            // Перша точка: зони ще немає в snapshot (0 точок), лише в
            // сирому файлі — додаємо її як нову DarkZonePolygon.
            updated.add(new DarkZonePolygon(name, List.of(pos)));
        } else {
            updated.replaceAll(z -> z.name().equalsIgnoreCase(name) ? z.withPoint(pos) : z);
        }
        writeAll(updated);
        return true;
    }

    /** true, якщо ім'я зони вже зарезервоване {@code create()} — незалежно від того, є в неї точки чи ще нема. */
    private static boolean existsRaw(String name) {
        if (find(name) != null) return true;
        Map<String, Object> root = read(file);
        for (Map<String, Object> zoneMap : readRawZones(root)) {
            String existing = text(zoneMap.get("name"));
            if (existing != null && existing.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean remove(String name) {
        List<DarkZonePolygon> updated = new ArrayList<>(snapshot);
        boolean removed = updated.removeIf(z -> z.name().equalsIgnoreCase(name));
        if (!removed) return false;
        writeAll(updated);
        return true;
    }

    public static void clearAll() {
        writeAll(List.of());
    }

    // ── Внутрішнє ────────────────────────────────────────────────────────

    private static void appendEmpty(String name) {
        // Порожня зона (0 точок) не проходить конструктор DarkZonePolygon
        // (вимагає ≥1 точку), тому в снапшоті поки не з'являється —
        // пишемо в файл маркер-запис одразу з першою точкою при першому
        // addPoint. create() лише резервує ім'я в YAML.
        Map<String, Object> root = read(file);
        List<Map<String, Object>> zones = new ArrayList<>(readRawZones(root));
        Map<String, Object> zoneMap = new LinkedHashMap<>();
        zoneMap.put("name", name);
        zoneMap.put("points", new ArrayList<>());
        zones.add(zoneMap);
        root.put(KEY, zones);
        write(file, root);
        reload();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readRawZones(Map<String, Object> root) {
        Object raw = root.get(KEY);
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static void writeAll(List<DarkZonePolygon> zones) {
        if (file == null) return;
        List<Map<String, Object>> values = new ArrayList<>();
        for (DarkZonePolygon zone : zones) {
            Map<String, Object> zoneMap = new LinkedHashMap<>();
            zoneMap.put("name", zone.name());
            List<Map<String, Object>> pointList = new ArrayList<>();
            for (BlockPos p : zone.points()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("x", p.getX());
                point.put("z", p.getZ());
                pointList.add(point);
            }
            zoneMap.put("points", pointList);
            values.add(zoneMap);
        }
        Map<String, Object> root = read(file);
        root.put(KEY, values);
        write(file, root);
        reload();
    }

    private static List<DarkZonePolygon> readZones(Map<String, Object> root) {
        List<Map<String, Object>> raw = readRawZones(root);
        List<DarkZonePolygon> result = new ArrayList<>();
        for (Map<String, Object> zoneMap : raw) {
            String name = text(zoneMap.get("name"));
            if (name == null || name.isBlank()) continue;
            List<BlockPos> points = readPoints(zoneMap.get("points"));
            if (points.isEmpty()) continue; // не готова зона (create() без жодного addPoint ще) — пропускаємо
            result.add(new DarkZonePolygon(name, points));
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> readPoints(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            result.add(new BlockPos(
                (int) number(map.get("x"), 0), 64, (int) number(map.get("z"), 0)));
        }
        return result;
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
        return value instanceof Number number ? number.doubleValue() : fallback;
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
