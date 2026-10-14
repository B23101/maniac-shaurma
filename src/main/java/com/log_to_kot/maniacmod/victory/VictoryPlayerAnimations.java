package com.log_to_kot.maniacmod.victory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.log_to_kot.maniacmod.ManiacMod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Перелік анімацій моделі гравця сцени перемоги, прочитаний прямо з
 * {@code animations/entity/victory_player.animation.json} — той самий підхід, що
 * {@code AirplaneIdleAnimations} у snipers_shaurma. Ключ, що починається з {@code dance}, — танець
 * живого; з {@code ghost} — політ привида. Нові варіанти додаються просто в json (Blockbench) —
 * код міняти не треба.
 */
public final class VictoryPlayerAnimations {

    private static final String PATH = "/assets/" + ManiacMod.MOD_ID + "/animations/entity/victory_player.animation.json";
    private static volatile List<String> dances;
    private static volatile List<String> ghosts;

    private VictoryPlayerAnimations() {}

    public static List<String> dances() {
        ensure();
        return dances;
    }

    public static List<String> ghosts() {
        ensure();
        return ghosts;
    }

    /** Назва анімації для варіанта (по колу). */
    public static String pick(boolean ghost, int variant) {
        List<String> list = ghost ? ghosts() : dances();
        if (list.isEmpty()) return ghost ? "ghost_a" : "dance_a";
        return list.get(Math.floorMod(variant, list.size()));
    }

    private static synchronized void ensure() {
        if (dances != null) return;
        List<String> d = new ArrayList<>();
        List<String> g = new ArrayList<>();
        try (InputStream in = VictoryPlayerAnimations.class.getResourceAsStream(PATH)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : root.getAsJsonObject("animations").keySet()) {
                    String k = key.substring(key.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
                    if (k.startsWith("dance")) d.add(key);
                    else if (k.startsWith("ghost")) g.add(key);
                }
            }
        } catch (Exception e) {
            ManiacMod.LOGGER.warn("[victory] не вдалось прочитати анімації гравця: {}", e.getMessage());
        }
        ghosts = g;
        dances = d;
    }
}
