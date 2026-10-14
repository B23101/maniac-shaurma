package com.log_to_kot.maniacmod.server.rating;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Файлове сховище оцінок маньяків — по одному JSON на гравця:
 * {@code <світ>/data/maniacmod/ratings/<uuid>.json}.
 *
 * <pre>
 * {
 *   "submitted": true,
 *   "playerName": "Steve",
 *   "timestamp": "2026-09-30T12:00:00Z",
 *   "comment": "...",
 *   "ratings": {
 *     "test_maniac": { "look": 4.5, "abilities": 5.0, "fear": 3.5 }
 *   }
 * }
 * </pre>
 *
 * Формат і розкладка каталогів — як у снайперів ({@code ModeRatingStorage}),
 * лише ключі критеріїв інші. Сюди нічого не потрапляє без валідації: її
 * робить {@link ManiacRatingService}, сховище лише читає й пише файли.
 */
public final class ManiacRatingStorage {

    private static final Logger LOGGER = LogManager.getLogger("ManiacRatingStorage");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ManiacRatingStorage() {}

    /** Дані одного гравця. {@code ratings}: id маньяка → [вигляд, здібності, страх]. */
    public record PlayerRatings(boolean submitted, String playerName, String timestamp,
                                String comment, Map<String, float[]> ratings) {}

    private static Path dir() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("Сервер не запущений");
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("maniacmod").resolve("ratings");
    }

    private static Path fileOf(UUID id) {
        return dir().resolve(id + ".json");
    }

    public static void save(UUID id, String playerName, Map<String, float[]> ratings, String comment) {
        try {
            Files.createDirectories(dir());

            JsonObject root = new JsonObject();
            root.addProperty("submitted", true);
            root.addProperty("playerName", playerName != null ? playerName : "???");
            root.addProperty("timestamp", Instant.now().toString());
            root.addProperty("comment", comment != null ? comment : "");

            JsonObject rat = new JsonObject();
            for (Map.Entry<String, float[]> e : ratings.entrySet()) {
                float[] r = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("look", r[0]);
                o.addProperty("abilities", r[1]);
                o.addProperty("fear", r[2]);
                rat.add(e.getKey(), o);
            }
            root.add("ratings", rat);

            try (Writer w = Files.newBufferedWriter(fileOf(id), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("[Ratings] Не вдалося зберегти оцінки {}: {}", id, ex.getMessage());
        }
    }

    /** @return {@code null} — гравець ще ніколи не оцінював. */
    public static PlayerRatings load(UUID id) {
        Path file;
        try {
            file = fileOf(id);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!Files.exists(file)) return null;

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return null;

            boolean submitted = root.has("submitted") && root.get("submitted").getAsBoolean();
            String name = root.has("playerName") ? root.get("playerName").getAsString() : "???";
            String ts = root.has("timestamp") ? root.get("timestamp").getAsString() : "";
            String comment = root.has("comment") ? root.get("comment").getAsString() : "";

            Map<String, float[]> map = new LinkedHashMap<>();
            if (root.has("ratings")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("ratings").entrySet()) {
                    JsonObject v = e.getValue().getAsJsonObject();
                    map.put(e.getKey(), new float[]{
                        v.has("look") ? v.get("look").getAsFloat() : 0f,
                        v.has("abilities") ? v.get("abilities").getAsFloat() : 0f,
                        v.has("fear") ? v.get("fear").getAsFloat() : 0f});
                }
            }
            return new PlayerRatings(submitted, name, ts, comment, map);
        } catch (Exception ex) {
            LOGGER.warn("[Ratings] Не вдалося прочитати оцінки {}: {}", id, ex.getMessage());
            return null;
        }
    }

    /** Усі збережені відгуки, новіші першими (для /maniac reviews). */
    public static List<PlayerRatings> loadAll() {
        List<PlayerRatings> result = new ArrayList<>();
        try {
            Path d = dir();
            if (!Files.exists(d)) return result;
            try (var stream = Files.list(d)) {
                stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try {
                        String name = p.getFileName().toString();
                        UUID id = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                        PlayerRatings pr = load(id);
                        if (pr != null) result.add(pr);
                    } catch (IllegalArgumentException ignored) {
                        // чужий файл у каталозі — пропускаємо
                    }
                });
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[Ratings] Не вдалося прочитати каталог оцінок: {}", ex.getMessage());
        }
        result.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
        return result;
    }
}
