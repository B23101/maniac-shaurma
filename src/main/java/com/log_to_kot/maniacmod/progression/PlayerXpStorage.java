package com.log_to_kot.maniacmod.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
 * Файлове сховище загального XP гравця: {@code <світ>/data/maniacmod/xp/<uuid>.json}
 * ({@code {"playerName":"Steve","totalXp":541}}). Та сама розкладка, що в оцінок маніяків
 * ({@code ManiacRatingStorage}). Рівень не зберігається — він похідний від {@link XpCurve}.
 */
public final class PlayerXpStorage {

    private static final Logger LOGGER = LogManager.getLogger("PlayerXpStorage");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PlayerXpStorage() {}

    private static Path dir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("Сервер не запущений");
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("maniacmod").resolve("xp");
    }

    public static long load(UUID id) {
        try {
            Path file = dir().resolve(id + ".json");
            if (!Files.exists(file)) return 0L;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject o = GSON.fromJson(r, JsonObject.class);
                return o != null && o.has("totalXp") ? Math.max(0L, o.get("totalXp").getAsLong()) : 0L;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("XP {} не читається: {}", id, e.getMessage());
            return 0L;
        }
    }

    public static void save(UUID id, String name, long totalXp) {
        try {
            Files.createDirectories(dir());
            JsonObject o = new JsonObject();
            o.addProperty("playerName", name == null ? "???" : name);
            o.addProperty("totalXp", Math.max(0L, totalXp));
            try (Writer w = Files.newBufferedWriter(dir().resolve(id + ".json"), StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("XP {} не збережено: {}", id, e.getMessage());
        }
    }
}
