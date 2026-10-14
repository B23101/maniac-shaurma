package com.log_to_kot.maniacmod.client.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Читає Blockbench-файл {@code player_animations/*.json} у {@link BoneClip}.
 *
 * ── Що вміє (усе — реальні форми з файлів проєкту) ───────────────────
 *   • значення ключа: голий масив {@code [x,y,z]}, або об'єкт
 *     {@code {"vector":[..]}}, або {@code {"pre":..,"post":..}} (усередині
 *     кожного — знов масив чи {@code {"vector":[..]}});
 *   • {@code lerp_mode}: {@code catmullrom}, {@code linear}, {@code step};
 *     без нього — лінійно (так робить сам Blockbench);
 *   • {@code loop} / {@code animation_length} на рівні анімації.
 *
 * ── Чого НЕ вміє — і каже про це, а не мовчить ───────────────────────
 * Molang-вирази (рядки на кшталт {@code "math.sin(query.anim_time)"}) у
 * значеннях ключів ми не виконуємо. Такий ключ ПРОПУСКАЄТЬСЯ, а причина
 * йде в {@code warnings} — щоб художник побачив у лозі, що саме не
 * підтримано, а не отримав тіло, яке мовчки не рухається.
 *
 * ── Чиста Java (крім Gson) ───────────────────────────────────────────
 * Без імпортів Minecraft: читає {@link Reader}, тож перевіряється тестом
 * без гри. Завантаження з ресурс-пака — справа {@code WakeUpAnimation}.
 */
public final class BoneClipLoader {

    private BoneClipLoader() {}

    /**
     * @param animationName ключ у {@code "animations"}; {@code null} — узяти
     *                      першу (у файлі кліпу зазвичай одна)
     * @param warnings      куди писати все, що не вдалося прочитати
     * @throws IOException  файл не JSON або в ньому немає жодної анімації
     */
    public static BoneClip load(Reader reader, String animationName,
                                Consumer<String> warnings) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IOException("корінь файлу не є JSON-об'єктом");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("не JSON: " + e.getMessage(), e);
        }

        JsonObject animations = root.has("animations") && root.get("animations").isJsonObject()
            ? root.getAsJsonObject("animations") : null;
        if (animations == null || animations.size() == 0) {
            throw new IOException("у файлі немає розділу \"animations\" або він порожній");
        }

        String chosen = animationName;
        if (chosen == null || !animations.has(chosen)) {
            if (chosen != null) {
                warnings.accept("анімації '" + chosen + "' у файлі немає — беру першу");
            }
            chosen = animations.keySet().iterator().next();
        }
        JsonObject anim = animations.getAsJsonObject(chosen);

        boolean loop = anim.has("loop") && anim.get("loop").isJsonPrimitive()
            && anim.get("loop").getAsJsonPrimitive().isBoolean() && anim.get("loop").getAsBoolean();

        float length = anim.has("animation_length") ? number(anim.get("animation_length"), 0f) : 0f;

        Map<String, Map<BoneClip.Channel, BoneClip.Track>> bones = new LinkedHashMap<>();
        float maxKeyTime = 0f;

        if (anim.has("bones") && anim.get("bones").isJsonObject()) {
            for (Map.Entry<String, JsonElement> boneEntry : anim.getAsJsonObject("bones").entrySet()) {
                String bone = normalizeBone(boneEntry.getKey());
                if (!boneEntry.getValue().isJsonObject()) continue;

                Map<BoneClip.Channel, BoneClip.Track> tracks = new EnumMap<>(BoneClip.Channel.class);
                for (Map.Entry<String, JsonElement> channelEntry : boneEntry.getValue().getAsJsonObject().entrySet()) {
                    BoneClip.Channel channel = channelOf(channelEntry.getKey());
                    if (channel == null) continue;   // напр. "relative_to" — нам не потрібне

                    List<BoneClip.Key> keys = readTrack(bone, channelEntry.getKey(),
                        channelEntry.getValue(), warnings);
                    if (keys.isEmpty()) continue;
                    for (BoneClip.Key key : keys) maxKeyTime = Math.max(maxKeyTime, key.time());
                    if (channel == BoneClip.Channel.SCALE) warnZeroScale(bone, keys, warnings);
                    tracks.put(channel, new BoneClip.Track(keys));
                }
                if (tracks.isEmpty()) continue;
                // Blockbench-експорт інколи містить ОДНУ кістку під двома іменами (rightItem і
                // right_item, rightArm і right_arm) — після normalizeBone це той самий ключ.
                // Раніше друга копія мовчки ПЕРЕПИСУВАЛА першу цілком (так статичне
                // right_item: [0,4,12] з кінця файлу «з'їдало» анімовану позицію rightItem).
                // Тепер канали зливаються; якщо канал визначено двічі — перемагає ПЕРШИЙ,
                // а про дубль іде попередження в лог, щоб його прибрали з файлу.
                Map<BoneClip.Channel, BoneClip.Track> existing = bones.get(bone);
                if (existing == null) {
                    bones.put(bone, tracks);
                } else {
                    for (Map.Entry<BoneClip.Channel, BoneClip.Track> e : tracks.entrySet()) {
                        if (existing.containsKey(e.getKey())) {
                            warnings.accept("кістка '" + bone + "' (" + boneEntry.getKey() + "): канал "
                                + e.getKey() + " уже заданий раніше під іншим іменем — дубль ігнорую");
                        } else {
                            existing.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }

        // Файл без animation_length — Blockbench такого не пише, але ручна
        // правка можлива. Тоді довжина = останній ключ: краще коротша
        // правдива анімація, ніж нескінченна нульова.
        if (length <= 0f) {
            length = maxKeyTime;
            warnings.accept("animation_length відсутній або 0 — беру час останнього ключа: " + length + " с");
        }

        return new BoneClip(chosen, length, loop, bones);
    }

    /**
     * Масштаб 0 на кістці = кістка (або предмет у руці) ЗНИКАЄ. У Blockbench це майже завжди
     * випадковий ключ (так було в {@code generator_repair.json}: right_item/left_item зі
     * scale 0 ховали предмет під час ремонту), тому тут — гучне попередження.
     */
    private static void warnZeroScale(String bone, List<BoneClip.Key> keys, Consumer<String> warnings) {
        for (BoneClip.Key key : keys) {
            for (float[] v : new float[][] { key.pre(), key.post() }) {
                for (float c : v) {
                    if (Math.abs(c) < 1.0e-3f) {
                        warnings.accept("кістка '" + bone + "': масштаб 0 на t=" + key.time()
                            + " — кістка/предмет зникне. Постав 1,1,1 (предмет у руці з нульовим "
                            + "масштабом ігнорується, див. HeldItemBones)");
                        return;
                    }
                }
            }
        }
    }

    // ── Розбір одного треку ─────────────────────────────────────────────

    private static List<BoneClip.Key> readTrack(String bone, String channelName,
                                                JsonElement raw, Consumer<String> warnings) {
        List<BoneClip.Key> keys = new ArrayList<>();
        String where = bone + "." + channelName;

        // Blockbench пише трек як {"<секунди>": <ключ>, ...}. Трек без
        // часу (просто масив) — це «стале значення на весь кліп».
        if (raw.isJsonArray()) {
            float[] v = vector(raw, where, warnings);
            if (v != null) keys.add(new BoneClip.Key(0f, v, v, BoneClip.Lerp.LINEAR));
            return keys;
        }
        if (!raw.isJsonObject()) {
            warnings.accept(where + ": невідомий формат треку — пропущено");
            return keys;
        }

        // Весь трек — ОДНЕ статичне значення, без часових ключів:
        //   "rotation": {"vector": [0, 0, 0]}
        // Blockbench так пише кістку, якій художник поставив позу без
        // анімації (реальний приклад — left_arm у generator_refuel.json).
        // Без цієї гілки слово "vector" читалось би як «час ключа» і
        // кістка мовчки лишалась би без треку.
        JsonObject trackObj = raw.getAsJsonObject();
        if (trackObj.has("vector") || trackObj.has("pre") || trackObj.has("post")) {
            float[] v;
            if (trackObj.has("vector")) {
                v = vector(trackObj, where, warnings);
            } else {
                float[] post = trackObj.has("post") ? vector(trackObj.get("post"), where, warnings) : null;
                float[] pre  = trackObj.has("pre")  ? vector(trackObj.get("pre"),  where, warnings) : null;
                v = post != null ? post : pre;
            }
            if (v != null) keys.add(new BoneClip.Key(0f, v, v, BoneClip.Lerp.LINEAR));
            return keys;
        }

        for (Map.Entry<String, JsonElement> frame : trackObj.entrySet()) {
            float time;
            try {
                time = Float.parseFloat(frame.getKey());
            } catch (NumberFormatException e) {
                warnings.accept(where + ": ключ часу '" + frame.getKey() + "' не число — пропущено");
                continue;
            }

            JsonElement value = frame.getValue();
            float[] pre;
            float[] post;
            BoneClip.Lerp lerp = BoneClip.Lerp.LINEAR;

            if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                if (obj.has("lerp_mode") && obj.get("lerp_mode").isJsonPrimitive()) {
                    lerp = switch (obj.get("lerp_mode").getAsString()) {
                        case "catmullrom" -> BoneClip.Lerp.CATMULLROM;
                        case "step"       -> BoneClip.Lerp.STEP;
                        case "linear"     -> BoneClip.Lerp.LINEAR;
                        default -> {
                            warnings.accept(where + "@" + time + ": lerp_mode '"
                                + obj.get("lerp_mode").getAsString() + "' не підтримується — лінійно");
                            yield BoneClip.Lerp.LINEAR;
                        }
                    };
                }

                if (obj.has("post") || obj.has("pre")) {
                    post = obj.has("post") ? vector(obj.get("post"), where + "@" + time, warnings) : null;
                    pre  = obj.has("pre")  ? vector(obj.get("pre"),  where + "@" + time, warnings) : null;
                    // Одна сторона є, інша ні — Blockbench має на увазі «однакові».
                    if (post == null) post = pre;
                    if (pre == null)  pre = post;
                } else {
                    pre = post = vector(value, where + "@" + time, warnings);
                }
            } else {
                pre = post = vector(value, where + "@" + time, warnings);
            }

            if (pre == null || post == null) continue;   // причину вже записано
            keys.add(new BoneClip.Key(time, pre, post, lerp));
        }
        return keys;
    }

    /** {@code [x,y,z]} або {@code {"vector":[x,y,z]}}; Molang-рядок → {@code null} + попередження. */
    private static float[] vector(JsonElement el, String where, Consumer<String> warnings) {
        JsonElement arr = el;
        if (el.isJsonObject() && el.getAsJsonObject().has("vector")) {
            arr = el.getAsJsonObject().get("vector");
        }
        if (!arr.isJsonArray()) {
            warnings.accept(where + ": очікував масив [x,y,z] — пропущено");
            return null;
        }
        JsonArray a = arr.getAsJsonArray();
        if (a.size() != 3) {
            warnings.accept(where + ": у векторі " + a.size() + " компонентів замість 3 — пропущено");
            return null;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            JsonElement c = a.get(i);
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) {
                // Рядок = Molang-вираз. Ми його не виконуємо — кажемо про це.
                warnings.accept(where + ": компонент " + i + " не число (Molang не підтримується) — ключ пропущено");
                return null;
            }
            out[i] = c.getAsFloat();
        }
        return out;
    }

    private static float number(JsonElement el, float fallback) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsFloat() : fallback;
    }

    private static BoneClip.Channel channelOf(String name) {
        return switch (name) {
            case "rotation" -> BoneClip.Channel.ROTATION;
            case "position" -> BoneClip.Channel.POSITION;
            case "scale"    -> BoneClip.Channel.SCALE;
            default -> null;
        };
    }

    /**
     * Приймає обидва стилі імен кісток, що трапляються в Blockbench-
     * експортах для гравця: {@code right_arm} (як у ваших файлах) і
     * {@code rightArm} (як у {@code flashlight_idle.json}); тулуб може бути
     * {@code torso} (як у {@code wounded.json}) або {@code body}.
     */
    private static String normalizeBone(String name) {
        return switch (name) {
            case "rightArm" -> BoneClip.RIGHT_ARM;
            case "leftArm"  -> BoneClip.LEFT_ARM;
            case "rightLeg" -> BoneClip.RIGHT_LEG;
            case "leftLeg"  -> BoneClip.LEFT_LEG;
            case "rightItem" -> "right_item";
            case "leftItem"  -> "left_item";
            // Blockbench-плагін Player Animation називає тулуб "torso"
            // (так у wounded.json), а ванільна модель і решта наших кліпів — "body".
            // Без цього трек тулуба мовчки губився, і нахилялась лише голова.
            case "torso"     -> BoneClip.BODY;
            default -> name;
        };
    }
}
