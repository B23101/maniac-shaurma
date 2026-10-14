package com.log_to_kot.maniacmod.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Файлове сховище історії. Розкладка (та сама родина, що в профілях і XP):
 *
 * <pre>
 *  &lt;світ&gt;/data/maniacmod/history/
 *      index.jsonl            один рядок JSON = один {@link MatchBrief} (короткий рядок списку)
 *      matches/&lt;id&gt;.json     повний {@link MatchRecord}
 * </pre>
 *
 * <p><b>Єдине джерело правди — файли матчів.</b> Індекс — лише прискорювач списку: якщо його нема або
 * він пошкоджений, він відбудовується з {@code matches/} ({@link #loadIndex}). Тож втрата індексу не
 * втрачає історію.</p>
 *
 * <p><b>Безпека.</b> {@code id} приходить від клієнта (запит деталей), тому перед будь-яким доступом до
 * файлу він перевіряється {@link #validId} — інакше {@code ../../x} вивів би за межі теки.</p>
 *
 * <p>Клас безстановий: шлях кореня й усе, що змінюється, передається параметрами. Стан (індекс у
 * пам'яті, кеш, потік запису) тримає {@link MatchHistoryService}.</p>
 */
final class MatchHistoryStorage {

    private static final Logger LOGGER = LogManager.getLogger("MatchHistoryStorage");
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new GsonBuilder().create();
    private static final Pattern ID = Pattern.compile("^\\d{8}-\\d{6}-[0-9a-f]{4}$");

    private MatchHistoryStorage() {}

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("maniacmod").resolve("history");
    }

    private static Path matchesDir(Path root) { return root.resolve("matches"); }

    private static Path indexFile(Path root) { return root.resolve("index.jsonl"); }

    // ── Запис ────────────────────────────────────────────────────────────

    /** Тимчасовий файл + переіменування: збій посеред запису не лишає обірваний JSON. */
    static void writeRecord(Path root, MatchRecord record) throws IOException {
        if (!validId(record.id)) throw new IOException("Некоректний id матчу: " + record.id);
        Path dir = matchesDir(root);
        Files.createDirectories(dir);
        Path target = dir.resolve(record.id + ".json");
        Path tmp = dir.resolve(record.id + ".json.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            PRETTY.toJson(record, w);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void appendIndex(Path root, MatchBrief brief) throws IOException {
        Files.createDirectories(root);
        String line = COMPACT.toJson(brief) + "\n";
        Files.writeString(indexFile(root), line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /** {@code null}, якщо матчу нема, id некоректний або файл не читається. */
    static MatchRecord readRecord(Path root, String id) {
        if (!validId(id)) return null;
        Path file = matchesDir(root).resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            MatchRecord rec = PRETTY.fromJson(r, MatchRecord.class);
            return rec == null ? null : rec.normalize();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Матч {} не читається: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Індекс за зростанням часу. Нема індексу — відбудовуємо з файлів матчів і одразу зберігаємо.
     * Битий рядок пропускається, а не валить усе завантаження.
     */
    static List<MatchBrief> loadIndex(Path root) {
        List<MatchBrief> out = new ArrayList<>();
        Path file = indexFile(root);
        boolean damaged = false;
        if (Files.isRegularFile(file)) {
            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isBlank()) continue;
                    try {
                        MatchBrief b = COMPACT.fromJson(line, MatchBrief.class);
                        if (b != null && validId(b.id)) out.add(b.normalize());
                        else damaged = true;
                    } catch (RuntimeException e) {
                        damaged = true;
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Індекс історії не читається, відбудовую: {}", e.getMessage());
                damaged = true;
            }
        }
        if (!Files.isRegularFile(file) || damaged) {
            out = rebuildIndex(root);
        }
        out.sort(Comparator.comparingLong((MatchBrief b) -> b.startedAtMs).thenComparing(b -> b.id));
        return out;
    }

    private static List<MatchBrief> rebuildIndex(Path root) {
        List<MatchBrief> out = new ArrayList<>();
        Path dir = matchesDir(root);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    MatchRecord rec = readRecord(root, name.substring(0, name.length() - ".json".length()));
                    if (rec != null) out.add(rec.toBrief());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Не вдалося перебрати матчі для індексу: {}", e.getMessage());
            }
        }
        out.sort(Comparator.comparingLong((MatchBrief b) -> b.startedAtMs).thenComparing(b -> b.id));
        try {
            Files.createDirectories(root);
            Path tmp = root.resolve("index.jsonl.tmp");
            StringBuilder sb = new StringBuilder();
            for (MatchBrief b : out) sb.append(COMPACT.toJson(b)).append('\n');
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, indexFile(root), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexFile(root), StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("Індекс історії відбудовано: {} матчів", out.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Індекс історії не збережено: {}", e.getMessage());
        }
        return out;
    }
}
