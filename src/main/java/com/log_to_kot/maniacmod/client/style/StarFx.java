package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Arrays;

/**
 * П'ятикутна зірка для екрана оцінок: заповнюється скан-лініями
 * ({@code fill} по горизонтальних смугах) — без текстур. Підтримує
 * обрізання по правому краю, з якого виходить ПОЛОВИНА зірки (оцінка
 * з кроком 0.5, як у снайперів).
 *
 * ── Чому не {@code overlay.notify.StarShape} ─────────────────────────
 * Той клас package-private і прив'язаний до оверлея оглушення; робити
 * його публічним заради іншого пакета означало б тягнути залежність
 * між непов'язаними модулями. Тут власна, публічна версія з обрізанням.
 */
public final class StarFx {

    private StarFx() {}

    /** Без обрізання. */
    public static final float NO_CLIP = Float.MAX_VALUE;

    /**
     * @param cx,cy      центр зірки, GUI-координати
     * @param outerR     радіус до кінчиків променів
     * @param argb       колір (із альфою)
     * @param clipRightX усе правіше цієї координати не малюється
     *                   ({@link #NO_CLIP} — малювати повністю)
     */
    public static void draw(GuiGraphics g, float cx, float cy, float outerR, int argb, float clipRightX) {
        if ((argb >>> 24) == 0 || outerR <= 0f) return;
        float innerR = outerR * 0.40f;
        float[] xs = new float[10];
        float[] ys = new float[10];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            float radius = (i % 2 == 0) ? outerR : innerR;
            double ang = -Math.PI / 2.0 + i * Math.PI / 5.0;
            xs[i] = cx + (float) (radius * Math.cos(ang));
            ys[i] = cy + (float) (radius * Math.sin(ang));
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        int yMin = (int) Math.floor(minY);
        int yMax = (int) Math.ceil(maxY);
        float[] hit = new float[10];
        for (int y = yMin; y < yMax; y++) {
            float sy = y + 0.5f;
            int n = 0;
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10;
                float y0 = ys[i];
                float y1 = ys[j];
                if ((y0 <= sy && y1 > sy) || (y1 <= sy && y0 > sy)) {
                    float t = (sy - y0) / (y1 - y0);
                    hit[n++] = xs[i] + t * (xs[j] - xs[i]);
                }
            }
            Arrays.sort(hit, 0, n);
            for (int k = 0; k + 1 < n; k += 2) {
                int x0 = Math.round(hit[k]);
                int x1 = Math.round(hit[k + 1]);
                if (clipRightX < x1) x1 = (int) Math.floor(clipRightX);
                if (x1 > x0) g.fill(x0, y, x1, y + 1, argb);
            }
        }
    }

    /** Зірка без обрізання. */
    public static void draw(GuiGraphics g, float cx, float cy, float outerR, int argb) {
        draw(g, cx, cy, outerR, argb, NO_CLIP);
    }
}
