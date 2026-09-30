package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Фонові ефекти {@link RoleRevealOverlay}: усе, що малюється ПІД текстом
 * оголошення ролі.
 *
 * <ul>
 *   <li>{@link #vignette} — м'яке затемнення/підсвічування країв екрана
 *       кольором ролі (у виживого блакитне, у маньяка криваво-червоне);</li>
 *   <li>{@link #flash} — короткий спалах усього екрана на «ударі» імені;</li>
 *   <li>{@link Blood} — кров, що стікає зверху екрана (лише маньяк):
 *       калюжа-«стеля» з нерівним краєм, нитки-патьоки з краплею на кінці
 *       й дрібні краплі, що зриваються вниз.</li>
 * </ul>
 *
 * <p>Жодних текстур: тільки {@code fill}/{@code fillGradient}, як і решта
 * оверлею. Стан крові (розташування патьоків, затримки, довжини) детермінований
 * від {@code seed} показу — на кожен показ кров інша, але всередині одного
 * показу не «пливе» між кадрами. Час іде від {@code t} (мс від старту показу),
 * тож ефект однаково працює, коли відкрите будь-яке меню.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RoleRevealFx {

    private RoleRevealFx() {}

    // ── Віньєтка ─────────────────────────────────────────────────────────

    /** Кількість смуг на кожен край: більше — плавніший градієнт без бандингу. */
    private static final int VIGNETTE_STEPS = 40;

    /**
     * Віньєтка кольору {@code rgb} по всіх чотирьох краях. Смуги різних країв
     * перекриваються в кутах, тож кути темніші/яскравіші за середини сторін —
     * саме так виглядає справжня віньєтка.
     *
     * @param strength 0..1 — множник до {@code maxAlpha}
     * @param maxAlpha 0..255 — альфа на самій границі екрана при strength = 1
     */
    public static void vignette(GuiGraphics g, int w, int h, int rgb, float strength, int maxAlpha) {
        float s = UiAnim.clamp01(strength);
        if (s < 0.01f || w <= 0 || h <= 0) return;

        float depthX = w * 0.20f;
        float depthY = h * 0.36f;
        int color = rgb & 0xFFFFFF;

        for (int i = 0; i < VIGNETTE_STEPS; i++) {
            float f = 1f - i / (float) VIGNETTE_STEPS;
            int a = Math.round(maxAlpha * s * (float) Math.pow(f, 2.2));
            if (a <= 0) break;
            int c = (a << 24) | color;

            int y0 = Math.round(depthY * i / VIGNETTE_STEPS);
            int y1 = Math.round(depthY * (i + 1) / VIGNETTE_STEPS);
            if (y1 > y0) {
                g.fill(0, y0, w, y1, c);
                g.fill(0, h - y1, w, h - y0, c);
            }
            int x0 = Math.round(depthX * i / VIGNETTE_STEPS);
            int x1 = Math.round(depthX * (i + 1) / VIGNETTE_STEPS);
            if (x1 > x0) {
                g.fill(x0, 0, x1, h, c);
                g.fill(w - x1, 0, w - x0, h, c);
            }
        }
    }

    /** Напівпрозорий спалах на весь екран (удар імені). */
    public static void flash(GuiGraphics g, int w, int h, int rgb, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        if (a <= 0) return;
        g.fill(0, 0, w, h, (a << 24) | (rgb & 0xFFFFFF));
    }

    // ── Кров ─────────────────────────────────────────────────────────────

    /** Кров, що стікає зверху екрана. Один екземпляр на оверлей, {@link #start} на кожен показ. */
    public static final class Blood {

        private static final int DRIPS = 28;
        /** Ширина колонки калюжі, px. */
        private static final int COL = 2;

        private static final int BODY = 0x8C0A12;
        private static final int DEEP = 0x4A0509;
        private static final int RIM = 0xD4222C;
        private static final int SHINE = 0xFF7A80;
        private static final int SHADOW = 0x3A0306;
        private static final int DROP = 0xA30F18;

        private static final float DROP_GRAVITY = 480f;

        private final float[] dripX = new float[DRIPS];     // 0..1 по ширині
        private final float[] delayMs = new float[DRIPS];
        private final float[] durMs = new float[DRIPS];
        private final float[] lenFrac = new float[DRIPS];   // частка висоти екрана
        private final float[] thick = new float[DRIPS];     // базова товщина, px при k = 1
        private final float[] progress = new float[DRIPS];  // 0..1 (кадровий кеш)
        private final UiFx.Sparks drops = new UiFx.Sparks(160);
        private float ph1, ph2, ph3;

        /** Розкладає патьоки для нового показу. */
        public void start(int seed) {
            long s = seed;
            for (int i = 0; i < DRIPS; i++) {
                dripX[i] = (i + 0.15f + 0.70f * hash01(s * 101L + i * 13L + 1L)) / DRIPS;
                delayMs[i] = 250f + 1650f * hash01(s * 103L + i * 17L + 2L);
                durMs[i] = 2200f + 1500f * hash01(s * 107L + i * 19L + 3L);
                float lq = hash01(s * 109L + i * 23L + 4L);
                lenFrac[i] = 0.14f + 0.50f * (float) Math.pow(lq, 1.4);
                thick[i] = 2.0f + 1.6f * lq + 1.2f * hash01(s * 113L + i * 29L + 5L);
            }
            ph1 = hash01(s * 127L + 6L) * 6.2832f;
            ph2 = hash01(s * 131L + 7L) * 6.2832f;
            ph3 = hash01(s * 137L + 8L) * 6.2832f;
            drops.clear();
        }

        public void clear() {
            drops.clear();
        }

        /**
         * @param k     масштаб інтерфейсу (від висоти екрана)
         * @param t     мс від старту показу
         * @param dt    секунди від попереднього кадру
         * @param alpha 0..1 — загальне згасання (на виході з показу)
         */
        public void render(GuiGraphics g, int sw, int sh, float k, long t, float dt, float alpha) {
            if (alpha < 0.02f) {
                drops.clear();
                return;
            }

            float poolP = UiAnim.easeOutCubic(UiAnim.clamp01((t - 120L) / 1500f));
            float poolH = sh * 0.034f * poolP;

            // Прогрес кожного патьока: різке падіння на початку, далі в'язке сповільнення.
            for (int i = 0; i < DRIPS; i++) {
                float p = UiAnim.clamp01((t - delayMs[i]) / durMs[i]);
                progress[i] = 1f - (float) Math.pow(1f - p, 2.4);
                if (p <= 0f) progress[i] = 0f;
            }

            // М'яка червона тінь під калюжею — «кров затікає в кадр».
            int shadowH = Math.round(poolH * 3.4f);
            if (shadowH > 2) {
                g.fillGradient(0, 0, sw, shadowH,
                    argb(SHADOW, alpha * 0.65f * poolP), 0x00000000);
            }

            drawPool(g, sw, k, poolH, alpha);

            for (int i = 0; i < DRIPS; i++) {
                if (progress[i] <= 0f) continue;
                drawDrip(g, sw, sh, k, i, poolH, dt, alpha);
            }

            drops.tickAndRender(g, dt, DROP_GRAVITY);
        }

        private void drawPool(GuiGraphics g, int sw, float k, float poolH, float alpha) {
            if (poolH < 1f) return;
            int body = argb(BODY, alpha * 0.94f);
            int deep = argb(DEEP, alpha * 0.85f);
            int rim = argb(RIM, alpha * 0.55f);

            for (int x = 0; x < sw; x += COL) {
                float fx = x + COL * 0.5f;
                // Нерівний край: три синусоїди різної частоти.
                float v = 0.5f
                    + 0.26f * (float) Math.sin(fx * 0.031f + ph1)
                    + 0.15f * (float) Math.sin(fx * 0.083f + ph2)
                    + 0.07f * (float) Math.sin(fx * 0.190f + ph3);
                float hgt = poolH * Math.max(0.15f, Math.min(1f, v));

                // Напливи над місцями, де починається патьок.
                for (int i = 0; i < DRIPS; i++) {
                    if (progress[i] <= 0f) continue;
                    float r = (thick[i] * 1.6f + 3f) * k;
                    float d = Math.abs(fx - dripX[i] * sw) / r;
                    if (d < 1f) {
                        hgt += poolH * 0.7f * (1f - d * d) * Math.min(1f, progress[i] * 6f);
                    }
                }

                int hi = Math.max(1, Math.round(hgt));
                g.fill(x, 0, x + COL, hi, body);
                g.fill(x, 0, x + COL, Math.max(1, Math.round(hgt * 0.5f)), deep);
                if (hi >= 3) g.fill(x, hi - 2, x + COL, hi, rim);
            }
        }

        private void drawDrip(GuiGraphics g, int sw, int sh, float k, int i,
                              float poolH, float dt, float alpha) {
            float p = progress[i];
            float x = dripX[i] * sw;
            float yTopF = poolH * 0.4f;
            float headF = Math.min(poolH * 0.5f + lenFrac[i] * sh * p, sh - 8f * k);

            int wi = Math.max(2, Math.round(thick[i] * k));
            int wi2 = Math.max(2, Math.round(wi * 0.72f));
            int yTop = Math.round(yTopF);
            int yHead = Math.round(headF);
            if (yHead <= yTop) return;
            int ySplit = yTop + Math.round((yHead - yTop) * 0.3f);

            // Нитка звужується до краплі: верхня третина ширша, нижня тонша.
            thread(g, Math.round(x - wi / 2f), yTop, ySplit, wi, alpha);
            thread(g, Math.round(x - wi2 / 2f), ySplit, yHead, wi2, alpha);

            // Крапля на кінці нитки.
            int bw = wi2 + 2;
            int bh = Math.max(4, Math.round(wi2 * 1.6f + 2f));
            int bx = Math.round(x - bw / 2f);
            int body = argb(BODY, alpha * 0.96f);
            g.fill(bx, yHead, bx + bw, yHead + bh - 1, body);
            g.fill(bx + 1, yHead + bh - 1, bx + bw - 1, yHead + bh, body);
            g.fill(bx, yHead, bx + 1, yHead + bh - 1, argb(RIM, alpha * 0.5f));
            g.fill(bx + bw - 1, yHead, bx + bw, yHead + bh - 1, argb(DEEP, alpha * 0.7f));
            g.fill(bx + 1, yHead + 1, bx + 2, yHead + 3, argb(SHINE, alpha * 0.7f));

            // Іноді від краплі відривається дрібна крапелька.
            if (alpha > 0.5f && p > 0.25f && p < 0.98f && Math.random() < dt * 0.22f) {
                float r = (float) Math.random();
                drops.emit(x + (r - 0.5f), yHead + bh, (r - 0.5f) * 6f, 20f + r * 30f,
                    0.85f, DROP, 2);
            }
        }

        /** Відрізок нитки: тіло, світлий блік зліва, темна тінь справа. */
        private static void thread(GuiGraphics g, int x0, int y0, int y1, int w, float alpha) {
            if (y1 <= y0) return;
            g.fill(x0, y0, x0 + w, y1, argb(BODY, alpha * 0.96f));
            g.fill(x0, y0, x0 + 1, y1, argb(RIM, alpha * 0.55f));
            g.fill(x0 + w - 1, y0, x0 + w, y1, argb(DEEP, alpha * 0.7f));
        }
    }

    // ── Дрібні помічники ─────────────────────────────────────────────────

    private static int argb(int rgb, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static float hash01(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return (v >>> 40) / (float) (1L << 24);
    }
}
