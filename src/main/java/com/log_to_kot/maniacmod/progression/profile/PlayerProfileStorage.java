package com.log_to_kot.maniacmod.progression.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.log_to_kot.maniacmod.progression.PlayerXpStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Файлове сховище профілю: {@code <світ>/data/maniacmod/profile/<uuid>.json}.
 * Та сама розкладка, що в {@link PlayerXpStorage} і оцінок маніяків.
 *
 * <p><b>Міграція.</b> Якщо профілю ще нема, а старий XP-файл є — весь накопичений досвід стає
 * XP виживого ({@link PlayerProfile#survivorXp}): до появи ролей досвід був спільним, а
 * екран підсумків показував його як «рівень гравця».</p>
 */
public final class PlayerProfileStorage {

    private static final Logger LOGGER = LogManager.getLogger("PlayerProfileStorage");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlayerProfileStorage() {}

    private static Path dir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("Сервер не запущений");
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("maniacmod").resolve("profile");
    }

    public static PlayerProfile load(UUID id) {
        Path file = null;
        try {
            file = dir().resolve(id + ".json");
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    PlayerProfile p = GSON.fromJson(r, PlayerProfile.class);
                    if (p != null) return p.normalize();
                }
                backupCorrupt(file);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Профіль {} не читається: {}", id, e.getMessage());
            if (file != null) backupCorrupt(file);
        }
        PlayerProfile fresh = new PlayerProfile();
        fresh.survivorXp = PlayerXpStorage.load(id);   // міграція зі старого спільного XP
        return fresh;
    }

    /** Пошкоджений файл не затираємо мовчки наступним збереженням — відкладаємо як {@code .corrupt}. */
    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Не вдалося відкласти пошкоджений профіль {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** Запис через тимчасовий файл + переіменування: збій посеред запису не лишає обірваний JSON. */
    public static void save(UUID id, String name, PlayerProfile profile) {
        if (name != null && !name.isEmpty()) profile.playerName = name;
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            com.google.gson.JsonObject o = GSON.toJsonTree(profile).getAsJsonObject();
            o.addProperty("playerName", profile.playerName == null || profile.playerName.isEmpty() ? "???" : profile.playerName);
            Path target = dir.resolve(id + ".json");
            Path tmp = dir.resolve(id + ".json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Лідерборд бачить новий стан одразу — без перечитування всієї теки.
            com.log_to_kot.maniacmod.leaderboard.LeaderboardService.onProfileSaved(id, profile);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Профіль {} не збережено: {}", id, e.getMessage());
        }
    }

    /**
     * Усі профілі світу (uuid → профіль) — джерело для лідербордів. Пошкоджені файли й тимчасові
     * {@code .tmp}/{@code .corrupt} пропускаються, але НЕ видаляються. Без запущеного сервера — порожньо.
     */
    public static java.util.Map<UUID, PlayerProfile> loadAll() {
        java.util.Map<UUID, PlayerProfile> out = new java.util.LinkedHashMap<>();
        Path dir;
        try {
            dir = dir();
        } catch (IllegalStateException e) {
            return out;
        }
        if (!Files.isDirectory(dir)) return out;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                if (!fn.endsWith(".json")) continue;
                UUID id;
                try {
                    id = UUID.fromString(fn.substring(0, fn.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    PlayerProfile p = GSON.fromJson(r, PlayerProfile.class);
                    if (p != null) out.put(id, p.normalize());
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Профіль {} пропущено в лідерборді: {}", fn, e.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Тека профілів не читається: {}", e.getMessage());
        }
        return out;
    }
}
