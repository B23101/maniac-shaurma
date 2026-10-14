package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.barricade.FixedBarricade;
import com.log_to_kot.maniacmod.barricade.FlexibleBarricade;
import dev.shaurmalib.libs.snakeyaml.DumperOptions;
import dev.shaurmalib.libs.snakeyaml.Yaml;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent-розмітка барикад у {@code barricade_layout.yml} — {@link ConfigSchema#BARRICADE_LAYOUT}
 * (DATA-блок). Наповнюється ЛИШЕ командами {@code /maniac barricade ...}: вміст ніколи не
 * відновлюється з дефолту (видалена барикада лишається видаленою) — той самий принцип, що
 * {@link DarkZoneConfigs}.
 *
 * <pre>
 * fixed:
 *   - {dimension: 'minecraft:overworld', x: 10, y: 64, z: -4, facing: north}
 * flexible:
 *   - id: b1
 *     dimension: 'minecraft:overworld'
 *     boards:
 *       - {ax: 1.5, ay: 65.0, az: 3.0, bx: 4.5, by: 66.0, bz: 3.0, nx: 0.0, ny: 0.0, nz: 1.0, block: 'minecraft:oak_planks'}
 * </pre>
 * Числа дощок пишуться як є (double): барикада в грі має збігатись із побудованою «точ-в-точ».
 */
public final class BarricadeConfigs {

    private static final String FIXED_KEY = "fixed";
    private static final String FLEXIBLE_KEY = "flexible";
    private static final Yaml YAML = buildYaml();

    private static volatile Path file;
    private static volatile List<FixedBarricade> fixed = List.of();
    private static volatile List<FlexibleBarricade> flexible = List.of();
    private static volatile long fileMtime = -1;

    private BarricadeConfigs() {}

    public static void init(Path namespaceDir) {
        file = namespaceDir.resolve(ConfigSchema.BARRICADE_LAYOUT.fileName());
        reload();
    }

    /** Знімки незмінні (List.copyOf) — звідки б не викликали, внутрішній стан не зламати. */
    public static List<FixedBarricade> fixed() { return fixed; }

    public static List<FlexibleBarricade> flexible() { return flexible; }

    public static FlexibleBarricade findFlexible(String id) {
        for (FlexibleBarricade b : flexible) {
            if (b.id().equalsIgnoreCase(id)) return b;
        }
        return null;
    }

    public static FixedBarricade findFixed(String dimension, BlockPos anyHalf) {
        for (FixedBarricade b : fixed) {
            if (b.sameAs(dimension, anyHalf)) return b;
        }
        return null;
    }

    /** Вільне ім'я виду {@code b1, b2, ...}. */
    public static String nextId() {
        int n = flexible.size() + 1;
        while (findFlexible("b" + n) != null) n++;
        return "b" + n;
    }

    public static void reload() {
        if (file == null) return;
        Map<String, Object> root = read(file);
        fixed = readFixed(root.get(FIXED_KEY));
        flexible = readFlexible(root.get(FLEXIBLE_KEY));
        fileMtime = mtime(file);
    }

    /** @return true, якщо файл змінили на диску і розмітку перечитано. */
    public static boolean tickWatcher() {
        if (file == null) return false;
        if (mtime(file) == fileMtime) return false;
        reload();
        return true;
    }

    // ── Зміни ────────────────────────────────────────────────────────────

    public static boolean addFlexible(FlexibleBarricade barricade) {
        if (findFlexible(barricade.id()) != null) return false;
        List<FlexibleBarricade> updated = new ArrayList<>(flexible);
        updated.add(barricade);
        writeAll(fixed, updated);
        return true;
    }

    public static boolean removeFlexible(String id) {
        List<FlexibleBarricade> updated = new ArrayList<>(flexible);
        if (!updated.removeIf(b -> b.id().equalsIgnoreCase(id))) return false;
        writeAll(fixed, updated);
        return true;
    }

    public static boolean addFixed(FixedBarricade barricade) {
        if (findFixed(barricade.dimension(), barricade.pos()) != null) return false;
        List<FixedBarricade> updated = new ArrayList<>(fixed);
        updated.add(barricade);
        writeAll(updated, flexible);
        return true;
    }

    public static boolean removeFixed(String dimension, BlockPos anyHalf) {
        List<FixedBarricade> updated = new ArrayList<>(fixed);
        if (!updated.removeIf(b -> b.sameAs(dimension, anyHalf))) return false;
        writeAll(updated, flexible);
        return true;
    }

    public static void clearAll() {
        writeAll(List.of(), List.of());
    }

    // ── Читання / запис ──────────────────────────────────────────────────

    private static List<FixedBarricade> readFixed(Object raw) {
        List<FixedBarricade> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return List.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Direction facing = Direction.byName(text(map.get("facing")));
            String dimension = text(map.get("dimension"));
            if (facing == null || !facing.getAxis().isHorizontal() || dimension == null) continue;
            result.add(new FixedBarricade(dimension, new BlockPos(
                (int) number(map.get("x")), (int) number(map.get("y")), (int) number(map.get("z"))), facing));
        }
        return List.copyOf(result);
    }

    private static List<FlexibleBarricade> readFlexible(Object raw) {
        List<FlexibleBarricade> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return List.of();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String id = text(map.get("id"));
            String dimension = text(map.get("dimension"));
            if (id == null || id.isBlank() || dimension == null) continue;
            List<BoardSpec> boards = new ArrayList<>();
            if (map.get("boards") instanceof List<?> rawBoards) {
                for (Object rawBoard : rawBoards) {
                    if (!(rawBoard instanceof Map<?, ?> b)) continue;
                    ResourceLocation block = ResourceLocation.tryParse(String.valueOf(b.get("block")));
                    if (block == null) block = new ResourceLocation("minecraft", "oak_planks");
                    boards.add(new BoardSpec(
                        new Vec3(number(b.get("ax")), number(b.get("ay")), number(b.get("az"))),
                        new Vec3(number(b.get("bx")), number(b.get("by")), number(b.get("bz"))),
                        new Vec3(number(b.get("nx")), number(b.get("ny")), number(b.get("nz"))),
                        block));
                }
            }
            if (boards.isEmpty()) continue;
            result.add(new FlexibleBarricade(id, dimension, boards));
        }
        return List.copyOf(result);
    }

    private static void writeAll(List<FixedBarricade> fixedList, List<FlexibleBarricade> flexibleList) {
        if (file == null) return;
        List<Map<String, Object>> fixedValues = new ArrayList<>();
        for (FixedBarricade b : fixedList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dimension", b.dimension());
            m.put("x", b.pos().getX());
            m.put("y", b.pos().getY());
            m.put("z", b.pos().getZ());
            m.put("facing", b.facing().getName().toLowerCase(Locale.ROOT));
            fixedValues.add(m);
        }
        List<Map<String, Object>> flexibleValues = new ArrayList<>();
        for (FlexibleBarricade b : flexibleList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("dimension", b.dimension());
            List<Map<String, Object>> boards = new ArrayList<>();
            for (BoardSpec s : b.boards()) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("ax", s.a().x); bm.put("ay", s.a().y); bm.put("az", s.a().z);
                bm.put("bx", s.b().x); bm.put("by", s.b().y); bm.put("bz", s.b().z);
                bm.put("nx", s.normal().x); bm.put("ny", s.normal().y); bm.put("nz", s.normal().z);
                bm.put("block", s.block().toString());
                boards.add(bm);
            }
            m.put("boards", boards);
            flexibleValues.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(FIXED_KEY, fixedValues);
        root.put(FLEXIBLE_KEY, flexibleValues);
        write(file, root);
        reload();
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

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
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
