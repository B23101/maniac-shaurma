package com.log_to_kot.maniacmod.intro;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import dev.shaurmalib.common.camera.replay.ReplayClip;
import dev.shaurmalib.common.camera.replay.ReplayInterpolation;
import dev.shaurmalib.common.camera.replay.ReplayPoint;
import dev.shaurmalib.common.camera.replay.ReplayScript;
import dev.shaurmalib.common.camera.replay.ReplayTransition;
import dev.shaurmalib.libs.snakeyaml.DumperOptions;
import dev.shaurmalib.libs.snakeyaml.Yaml;

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
 * Replay-ролики вступу: файл {@code replays.yml} у теці конфігів мода.
 * Скільки завгодно роликів, у кожному скільки завгодно точок.
 *
 * <pre>
 * replays:
 *   - id: fly_over            # унікальне ім'я
 *     audience: ALL           # ALL | SURVIVOR | MANIAC — кому показувати
 *     interpolation: SPLINE   # LINEAR | SMOOTH | SPLINE
 *     sharedIntervalMs: 3000  # спільний інтервал між точками
 *     cutAtMs: 0              # примусово обірвати ролик на цій мс (0 — не обривати)
 *     transition: CUT         # як потрапляємо на цей ролик: CUT | DIP_TO_BLACK
 *     dipMs: 600
 *     shake: 0.3              # «рука оператора», градуси (0 — статично)
 *     points:
 *       - {x: 10.5, y: 80.0, z: -3.2, yaw: 45.0, pitch: 12.0, fov: 0.0, intervalMs: 0}
 * </pre>
 *
 * {@code intervalMs} у точці &gt; 0 перекриває спільний інтервал для відрізка
 * «ця точка → наступна». Ролики грають підряд у порядку файлу; усі разом
 * обмежені стелею (вікном вступу) — див. {@link IntroTimeline#windowMs()}.
 *
 * Точки зручно ставити командами {@code /maniac replay ...} прямо з позиції
 * й погляду адміна. Файл перечитується при кожному запуску вступу — правки yml
 * діють без перезапуску.
 */
public final class IntroReplayConfigs {

    public static final String FILE_NAME = "replays.yml";
    private static final Yaml YAML = buildYaml();

    private IntroReplayConfigs() {}

    // ── Читання ──────────────────────────────────────────────────────────

    /** Усі ролики, придатні до показу (порожні/биті пропускаються з попередженням). */
    public static synchronized List<ReplayClip> clips() {
        List<ReplayClip> out = new ArrayList<>();
        for (Map<String, Object> raw : rawClips(read())) {
            try {
                ReplayClip clip = parse(raw);
                if (clip != null) out.add(clip);
            } catch (RuntimeException e) {
                ManiacMod.LOGGER.warn("[intro] ролик '{}' пропущено: {}", raw.get("id"), e.getMessage());
            }
        }
        return out;
    }

    /**
     * Сценарій для гравця з урахуванням його ролі. Стеля сценарію — {@code windowMs}:
     * що б не було в роликах, вступ завершується рівно у визначений момент.
     */
    public static ReplayScript scriptFor(boolean maniac, int windowMs) {
        String role = maniac ? "MANIAC" : "SURVIVOR";
        List<ReplayClip> picked = new ArrayList<>();
        for (ReplayClip clip : clips()) {
            String a = clip.audience();
            if (a.equalsIgnoreCase("ALL") || a.equalsIgnoreCase(role)) picked.add(clip);
        }
        return picked.isEmpty() ? ReplayScript.empty() : ReplayScript.of(windowMs, picked);
    }

    private static ReplayClip parse(Map<String, Object> m) {
        String id = str(m.get("id"), null);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("немає id");
        List<Map<String, Object>> pts = rawPoints(m);
        if (pts.isEmpty()) return null;
        ReplayClip.Builder b = ReplayClip.builder(id)
            .audience(str(m.get("audience"), "ALL").toUpperCase(Locale.ROOT))
            .interpolation(enumOf(ReplayInterpolation.class, m.get("interpolation"), ReplayInterpolation.SPLINE))
            .sharedIntervalMs((int) num(m.get("sharedIntervalMs"), ReplayClip.DEFAULT_SHARED_INTERVAL_MS))
            .cutAtMs((int) num(m.get("cutAtMs"), 0))
            .transition(enumOf(ReplayTransition.class, m.get("transition"), ReplayTransition.CUT),
                (int) num(m.get("dipMs"), 600))
            .shake((float) num(m.get("shake"), 0));
        for (Map<String, Object> p : pts) {
            b.point(new ReplayPoint(num(p.get("x"), 0), num(p.get("y"), 64), num(p.get("z"), 0),
                (float) num(p.get("yaw"), 0), (float) num(p.get("pitch"), 0),
                (float) num(p.get("fov"), 0), (int) num(p.get("intervalMs"), 0)));
        }
        return b.build();
    }

    // ── Редагування (для команд) ─────────────────────────────────────────

    /** Додає точку в ролик (створює ролик, якщо його ще нема). Повертає кількість точок у ролику. */
    public static synchronized int addPoint(String clipId, double x, double y, double z,
                                            float yaw, float pitch, int intervalMs) {
        Map<String, Object> root = read();
        Map<String, Object> clip = findOrCreate(root, clipId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", round(x)); p.put("y", round(y)); p.put("z", round(z));
        p.put("yaw", round(yaw)); p.put("pitch", round(pitch));
        p.put("fov", 0.0);
        p.put("intervalMs", intervalMs);
        List<Map<String, Object>> pts = rawPoints(clip);
        pts.add(p);
        clip.put("points", pts);
        write(root);
        return pts.size();
    }

    /** {@code false} — ролика або точки нема. */
    public static synchronized boolean removePoint(String clipId, int index) {
        Map<String, Object> root = read();
        Map<String, Object> clip = find(root, clipId);
        if (clip == null) return false;
        List<Map<String, Object>> pts = rawPoints(clip);
        if (index < 0 || index >= pts.size()) return false;
        pts.remove(index);
        clip.put("points", pts);
        write(root);
        return true;
    }

    public static synchronized boolean removeClip(String clipId) {
        Map<String, Object> root = read();
        List<Map<String, Object>> list = rawClips(root);
        boolean removed = list.removeIf(c -> clipId.equalsIgnoreCase(str(c.get("id"), "")));
        if (removed) { root.put("replays", list); write(root); }
        return removed;
    }

    public static synchronized void clearAll() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("replays", new ArrayList<>());
        write(root);
    }

    /** Змінює поле ролика ({@code sharedIntervalMs}, {@code cutAtMs}, {@code shake}, {@code interpolation}, {@code audience}, {@code transition}, {@code dipMs}). */
    public static synchronized boolean setClipField(String clipId, String field, Object value) {
        Map<String, Object> root = read();
        Map<String, Object> clip = find(root, clipId);
        if (clip == null) return false;
        clip.put(field, value);
        write(root);
        return true;
    }

    /** Змінює поле точки ({@code intervalMs}, {@code fov}). */
    public static synchronized boolean setPointField(String clipId, int index, String field, Object value) {
        Map<String, Object> root = read();
        Map<String, Object> clip = find(root, clipId);
        if (clip == null) return false;
        List<Map<String, Object>> pts = rawPoints(clip);
        if (index < 0 || index >= pts.size()) return false;
        pts.get(index).put(field, value);
        clip.put("points", pts);
        write(root);
        return true;
    }

    /** Короткий опис усіх роликів для {@code /maniac replay list}. */
    public static synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> raw : rawClips(read())) {
            int pts = rawPoints(raw).size();
            lines.add(str(raw.get("id"), "?") + " — точок " + pts
                + ", " + str(raw.get("interpolation"), "SPLINE")
                + ", спільний інтервал " + (int) num(raw.get("sharedIntervalMs"), ReplayClip.DEFAULT_SHARED_INTERVAL_MS) + " мс"
                + ", обрив " + (int) num(raw.get("cutAtMs"), 0) + " мс"
                + ", для " + str(raw.get("audience"), "ALL"));
        }
        return lines;
    }

    // ── YAML ─────────────────────────────────────────────────────────────

    private static Path file() {
        return ManiacConfigs.namespaceDirectory().resolve(FILE_NAME);
    }

    private static Map<String, Object> read() {
        Path path = file();
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            Object raw = YAML.load(Files.readString(path, StandardCharsets.UTF_8));
            return raw instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.warn("[intro] {} не читається: {}", FILE_NAME, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static void write(Map<String, Object> root) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, YAML.dump(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ManiacMod.LOGGER.error("[intro] не вдалось записати {}: {}", FILE_NAME, e.getMessage());
        }
    }

    private static List<Map<String, Object>> rawClips(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (root.get("replays") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(castMap(m));
        }
        return out;
    }

    private static List<Map<String, Object>> rawPoints(Map<String, Object> clip) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (clip.get("points") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(castMap(m));
        }
        return out;
    }

    private static Map<String, Object> find(Map<String, Object> root, String id) {
        for (Map<String, Object> c : rawClips(root)) {
            if (id.equalsIgnoreCase(str(c.get("id"), ""))) return c;
        }
        return null;
    }

    /** Знаходить ролик у {@code root} або додає новий із типовими полями. */
    private static Map<String, Object> findOrCreate(Map<String, Object> root, String id) {
        List<Map<String, Object>> list = rawClips(root);
        for (Map<String, Object> c : list) {
            if (id.equalsIgnoreCase(str(c.get("id"), ""))) { root.put("replays", list); return c; }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("audience", "ALL");
        c.put("interpolation", "SPLINE");
        c.put("sharedIntervalMs", ReplayClip.DEFAULT_SHARED_INTERVAL_MS);
        c.put("cutAtMs", 0);
        c.put("transition", "CUT");
        c.put("dipMs", 600);
        c.put("shake", 0.0);
        c.put("points", new ArrayList<>());
        list.add(c);
        root.put("replays", list);
        return c;
    }

    // ── Дрібниці ─────────────────────────────────────────────────────────

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object v, E fallback) {
        if (v == null) return fallback;
        try {
            return Enum.valueOf(type, String.valueOf(v).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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
