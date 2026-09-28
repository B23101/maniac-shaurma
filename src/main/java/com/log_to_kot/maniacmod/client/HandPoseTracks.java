package com.log_to_kot.maniacmod.client;

/**
 * Ключові кадри рук із Blockbench-кліпів ({@code generator_repair.json},
 * {@code generator_refuel.json}, {@code pick_up_item.json}), перенесені 1:1
 * і відтворювані лінійною/косинусною інтерполяцією.
 *
 * Навіщо: PAL-шар ({@code PlayerPoseController}) для цих поз не грає, а
 * прямий запис у {@code PlayerModel.setupAnim} (як у ліхтарика) працює
 * надійно. Кути тут — ГРАДУСИ з файлів; {@link #sample} повертає РАДІАНИ
 * для {@code ModelPart}. Зміниш кліп у Blockbench — онови масиви нижче.
 *
 * Формат рядка: {t(сек), x, y, z}. Позиції — в пікселях моделі (ModelPart.x/y/z).
 */
public final class HandPoseTracks {

    private HandPoseTracks() {}

    // ── generator_repair (цикл 5.7917 с) ────────────────────────────────
    public static final float REPAIR_LEN = 5.7917f;
    public static final float[][] REPAIR_R_ROT = {
        {0.0f, -87.5f, 0f, 0f}, {0.5417f, -82.5474f, -5.0331f, 0.2171f},
        {1.0f, -85.0763f, -0.0752f, 0.8648f}, {1.5417f, -90.0581f, 4.9063f, 1.295f},
        {2.625f, -80.3389f, -4.2674f, 1.3042f}, {3.9167f, -85.3514f, 3.1266f, 2.5612f},
        {4.5f, -75.3514f, 3.1266f, 2.5612f}, {5.7917f, -87.5f, 0f, 0f}};
    public static final float[][] REPAIR_R_POS = {
        {0.0f, 0, 0, 0}, {2.625f, 0, 0, 0.42f}, {4.5f, 0, 0, -1f},
        {4.9167f, 0, 0, 0}, {5.7917f, 0, 0, 0}};
    public static final float[][] REPAIR_L_ROT = {
        {0.0f, -77.5f, 0f, 0f}, {1.0f, -77.4538f, 4.8812f, 1.0848f},
        {2.125f, -89.9538f, 4.8812f, 1.0848f}, {2.875f, -79.0366f, 5.9481f, 9.0553f},
        {3.875f, -90.8764f, 5.9614f, -8.5404f}, {5.0f, -98.3065f, 5.9614f, -8.5404f},
        {5.7917f, -77.5f, 0f, 0f}};
    public static final float[][] REPAIR_L_POS = {
        {0.0f, 0, 0, 0}, {4.0417f, 0, 0, 1.19f}, {5.0f, 0, 0, -1f}, {5.7917f, 0, 0, 0}};

    // ── generator_refuel (цикл 1.75 с; ліва рука без ключів → ванільна) ──
    public static final float REFUEL_LEN = 1.75f;
    public static final float[][] REFUEL_R_ROT = {
        {0.0f, -67.5f, 0f, 0f}, {0.9583f, -67.5193f, 0.9564f, -2.3099f},
        {1.3333f, -67.5194f, 0.9564f, -2.3098f}, {1.75f, -67.5f, 0f, 0f}};
    public static final float[][] REFUEL_R_POS = {
        {0.0f, 0, 0, 0}, {0.4583f, 0, -0.2f, 0}, {0.9583f, 0, -0.45f, 0}, {1.75f, 0, 0, 0}};

    // ── pick_up_item (одноразовий, 0.9167 с = 18.3 тіка) ─────────────────
    public static final float PICKUP_LEN = 0.9167f;
    public static final float[][] PICKUP_R_ROT = {
        {0.0f, 0, 0, 0}, {0.0833f, -12.5f, 0, 30f}, {0.2083f, -52.735f, -10.9701f, 27.889f},
        {0.3333f, -76.1041f, -19.7347f, 40.5296f}, {0.5f, -63.6041f, -19.7347f, 40.5296f},
        {0.6667f, 4.5857f, -20.2135f, 45.8252f}, {0.7917f, 12.4724f, -12.2177f, 12.5405f},
        {0.9167f, 0, 0, 0}};
    public static final float[][] PICKUP_R_POS = {
        {0.0f, 0, 0, 0}, {0.2083f, 0, 0, -3f}, {0.4167f, 0, 0, -3f}, {0.9167f, 0, 0, 0}};

    /**
     * Значення треку в момент {@code t} (сек). {@code loop} — зациклити за
     * {@code len}; інакше тримає останній кадр. Повертає 3 компоненти.
     */
    public static void sample(float[][] track, float t, float len, boolean loop, float[] out) {
        if (loop && len > 0f) t = t % len;
        if (t <= track[0][0]) { copy(track[0], out); return; }
        float[] last = track[track.length - 1];
        if (t >= last[0]) { copy(last, out); return; }
        for (int i = 0; i < track.length - 1; i++) {
            float[] a = track[i], b = track[i + 1];
            if (t >= a[0] && t <= b[0]) {
                float span = b[0] - a[0];
                float k = span <= 0f ? 0f : (t - a[0]) / span;
                // Косинусне згладжування ≈ те, що PAL робить для catmullrom (INOUTSINE).
                k = 0.5f - 0.5f * (float) Math.cos(Math.PI * k);
                out[0] = a[1] + (b[1] - a[1]) * k;
                out[1] = a[2] + (b[2] - a[2]) * k;
                out[2] = a[3] + (b[3] - a[3]) * k;
                return;
            }
        }
        copy(last, out);
    }

    private static void copy(float[] key, float[] out) {
        out[0] = key[1]; out[1] = key[2]; out[2] = key[3];
    }

    public static float rad(float deg) { return deg * ((float) Math.PI / 180f); }
}
