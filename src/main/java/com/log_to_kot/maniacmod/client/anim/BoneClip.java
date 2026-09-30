package com.log_to_kot.maniacmod.client.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Один Blockbench-кліп тіла гравця, розібраний у пам'ять: набір кісток,
 * у кожної — до трьох треків (обертання, позиція, масштаб).
 *
 * ── Навіщо, якщо є {@code HandPoseTracks} ────────────────────────────
 * {@code HandPoseTracks} — це числа з JSON, ПЕРЕПИСАНІ РУКАМИ в Java:
 * змінив кліп у Blockbench — мусиш знову переносити масиви. Для нової
 * анімації це не годиться: файл {@code wake_up.json} має підкладатись
 * як є, без жодної правки коду. Тому кліп читається з самого файлу
 * ({@link BoneClipLoader}), а цей клас лише вміє відповісти «яке значення
 * кістки в момент {@code t}».
 *
 * ── Чиста Java ──────────────────────────────────────────────────────
 * Жодного імпорту Minecraft: клас можна перевірити звичайним
 * JUnit-тестом, без запуску гри (так само, як DSP голосу маньяка).
 *
 * ── Одиниці ─────────────────────────────────────────────────────────
 * Файл зберігає ГРАДУСИ і ПІКСЕЛІ моделі — саме так, як їх пише
 * Blockbench. {@link #sample} повертає їх без перетворення: конвертація
 * у радіани/блоки — справа того, хто застосовує кліп до моделі.
 */
public final class BoneClip {

    /** Імена кісток, які ми вміємо застосувати до {@code PlayerModel}. */
    public static final String HEAD = "head";
    public static final String BODY = "body";
    public static final String RIGHT_ARM = "right_arm";
    public static final String LEFT_ARM = "left_arm";
    public static final String RIGHT_LEG = "right_leg";
    public static final String LEFT_LEG = "left_leg";

    /** Що саме анімує трек. */
    public enum Channel { ROTATION, POSITION, SCALE }

    private final String name;
    private final float length;
    private final boolean loop;
    private final Map<String, Map<Channel, Track>> bones;

    BoneClip(String name, float length, boolean loop, Map<String, Map<Channel, Track>> bones) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.bones = bones;
    }

    public String name()   { return name; }
    /** Тривалість кліпу в секундах ({@code animation_length} із файлу). */
    public float length()  { return length; }
    public boolean loops() { return loop; }

    /** Чи анімує кліп цю кістку хоч одним треком. */
    public boolean hasBone(String bone) {
        return bones.containsKey(bone);
    }

    /** Чи є в кістки трек цього каналу. */
    public boolean hasChannel(String bone, Channel channel) {
        Map<Channel, Track> tracks = bones.get(bone);
        return tracks != null && tracks.containsKey(channel);
    }

    /**
     * Значення каналу кістки в момент {@code seconds}.
     *
     * @param out масив із 3 компонентів (x, y, z), який буде заповнено
     * @return {@code false}, якщо в кістки немає такого треку — тоді
     *         {@code out} не змінюється, і викликач лишає ванільне
     *         значення (так само, як робить Blockbench: кістка без ключів
     *         не рухається).
     */
    public boolean sample(String bone, Channel channel, float seconds, float[] out) {
        Map<Channel, Track> tracks = bones.get(bone);
        if (tracks == null) return false;
        Track track = tracks.get(channel);
        if (track == null) return false;

        float t = seconds;
        if (loop && length > 0f) {
            t = t % length;
            if (t < 0f) t += length;
        }
        track.sample(t, out);
        return true;
    }

    /** Імена кісток, які анімує кліп (для діагностики в логах). */
    public Iterable<String> boneNames() {
        return Collections.unmodifiableSet(bones.keySet());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Трек — впорядковані ключові кадри одного каналу
    // ═══════════════════════════════════════════════════════════════════

    /** Режим інтерполяції МІЖ цим ключем і наступним. */
    enum Lerp { LINEAR, CATMULLROM, STEP }

    /**
     * Ключ. Blockbench розрізняє значення «до» ключа ({@code pre}) і «після»
     * ({@code post}) — різні вони лише коли художник зробив різкий
     * стрибок на цьому кадрі; у звичайному кадрі {@code pre == post}.
     */
    record Key(float time, float[] pre, float[] post, Lerp lerp) {}

    static final class Track {
        private final List<Key> keys;

        Track(List<Key> keys) {
            this.keys = new ArrayList<>(keys);
            this.keys.sort((a, b) -> Float.compare(a.time(), b.time()));
        }

        List<Key> keys() { return keys; }

        void sample(float t, float[] out) {
            int n = keys.size();
            if (n == 0) return;

            Key first = keys.get(0);
            if (t <= first.time()) { copy(first.post(), out); return; }
            Key last = keys.get(n - 1);
            if (t >= last.time())  { copy(last.pre(), out);  return; }

            for (int i = 0; i < n - 1; i++) {
                Key a = keys.get(i);
                Key b = keys.get(i + 1);
                if (t < a.time() || t > b.time()) continue;

                float span = b.time() - a.time();
                float k = span <= 0f ? 1f : (t - a.time()) / span;

                float[] from = a.post();
                float[] to = b.pre();

                switch (a.lerp()) {
                    case STEP -> copy(from, out);
                    case CATMULLROM -> catmull(i, k, from, to, out);
                    default -> {
                        for (int c = 0; c < 3; c++) out[c] = from[c] + (to[c] - from[c]) * k;
                    }
                }
                return;
            }
            copy(last.pre(), out);
        }

        /**
         * Catmull-Rom по сусідніх ключах. На краях треку немає сусіда —
         * дублюємо крайній ключ (так само роблять і Blockbench, і
         * PlayerAnimator), тож на першому/останньому відрізку крива
         * плавно виходить на цільове значення без «перельоту».
         */
        private void catmull(int i, float k, float[] p1, float[] p2, float[] out) {
            float[] p0 = i > 0 ? keys.get(i - 1).post() : p1;
            float[] p3 = i + 2 < keys.size() ? keys.get(i + 2).pre() : p2;
            float k2 = k * k;
            float k3 = k2 * k;
            for (int c = 0; c < 3; c++) {
                out[c] = 0.5f * ((2f * p1[c])
                    + (-p0[c] + p2[c]) * k
                    + (2f * p0[c] - 5f * p1[c] + 4f * p2[c] - p3[c]) * k2
                    + (-p0[c] + 3f * p1[c] - 3f * p2[c] + p3[c]) * k3);
            }
        }

        private static void copy(float[] src, float[] out) {
            out[0] = src[0]; out[1] = src[1]; out[2] = src[2];
        }
    }
}
