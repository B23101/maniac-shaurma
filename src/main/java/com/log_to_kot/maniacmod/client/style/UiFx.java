package com.log_to_kot.maniacmod.client.style;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Спільні ЕФЕКТИ для HUD/екранів у стилі панелей генератора
 * ({@link GeneratorFuelUiTheme}): усе, що потрібно понад голі
 * {@code fill}-примітиви — тонований blit текстур, м'яке світіння,
 * «дихаючі» кутові скоби, ЕКГ-лінія, ковзний промінь, іскри.
 *
 * <p>Клас безстановий (окрім {@link Sparks}, який тримає власний оверлей).
 * Жодного {@code scissor}: усі ефекти обрізаються вручну, бо
 * {@code GuiGraphics#enableScissor} не враховує {@code PoseStack} і
 * ламався б усередині {@code beginAppear} (масштаб/зсув).</p>
 */
public final class UiFx {

    private UiFx() {}

    // ── Текстури ─────────────────────────────────────────────────────────

    /**
     * Blit області текстури з множником кольору (RGB) і альфи. Поточний
     * множник ({@code RenderSystem.getShaderColor}) ЗБЕРІГАЄТЬСЯ й
     * відновлюється, а альфа ПЕРЕМНОЖУЄТЬСЯ на нього — тому працює всередині
     * панелі з fade-in.
     */
    public static void blitTinted(GuiGraphics g, ResourceLocation tex, int x, int y,
                                  float u, float v, int w, int h, int texW, int texH,
                                  int rgb, float alpha) {
        if (w <= 0 || h <= 0) return;
        float[] prev = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(
            prev[0] * ((rgb >> 16) & 0xFF) / 255f,
            prev[1] * ((rgb >> 8) & 0xFF) / 255f,
            prev[2] * (rgb & 0xFF) / 255f,
            prev[3] * Math.max(0f, Math.min(1f, alpha)));
        g.blit(tex, x, y, u, v, w, h, texW, texH);
        RenderSystem.setShaderColor(prev[0], prev[1], prev[2], prev[3]);
    }

    /** Звичайний blit усієї текстури без тонування. */
    public static void blitFull(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h) {
        blitTinted(g, tex, x, y, 0f, 0f, w, h, w, h, 0xFFFFFF, 1f);
    }

    // ── Світіння ─────────────────────────────────────────────────────────

    /**
     * М'яке світіння ЗОВНІ прямокутника: кілька 1px-рамок із швидко
     * спадаючою альфою. {@code strength} 0..1.
     */
    public static void glow(GuiGraphics g, int x, int y, int w, int h, int rgb, float strength, int layers) {
        if (strength <= 0.01f) return;
        for (int i = 1; i <= layers; i++) {
            float k = 1f - (i - 1) / (float) layers;
            int c = GeneratorFuelUiTheme.alpha(0xFF000000 | (rgb & 0xFFFFFF), strength * 0.34f * k * k);
            ManiacUiTheme.border1px(g, x - i, y - i, w + i * 2, h + i * 2, c);
        }
    }

    /** Світіння ВСЕРЕДИНІ прямокутника (внутрішні рамки) — «підсвічена» панель. */
    public static void innerGlow(GuiGraphics g, int x, int y, int w, int h, int rgb, float strength, int layers) {
        if (strength <= 0.01f) return;
        for (int i = 0; i < layers; i++) {
            float k = 1f - i / (float) layers;
            int c = GeneratorFuelUiTheme.alpha(0xFF000000 | (rgb & 0xFFFFFF), strength * 0.40f * k * k);
            ManiacUiTheme.border1px(g, x + 1 + i, y + 1 + i, w - 2 - i * 2, h - 2 - i * 2, c);
        }
    }

    // ── Скоби ────────────────────────────────────────────────────────────

    /**
     * Чотири кутові скоби-«L». {@code open} 0..1 — наскільки вони
     * розведені від кутів назовні (0 = притиснуті, 1 = відступ {@code spread}
     * px): анімація «наведення/виділення».
     */
    public static void brackets(GuiGraphics g, int x, int y, int w, int h, int argb, int len, float open, int spread) {
        int o = Math.round(open * spread);
        int x0 = x - o, y0 = y - o, x1 = x + w + o, y1 = y + h + o;
        // верх-ліво
        g.fill(x0, y0, x0 + len, y0 + 1, argb);
        g.fill(x0, y0, x0 + 1, y0 + len, argb);
        // верх-право
        g.fill(x1 - len, y0, x1, y0 + 1, argb);
        g.fill(x1 - 1, y0, x1, y0 + len, argb);
        // низ-ліво
        g.fill(x0, y1 - 1, x0 + len, y1, argb);
        g.fill(x0, y1 - len, x0 + 1, y1, argb);
        // низ-право
        g.fill(x1 - len, y1 - 1, x1, y1, argb);
        g.fill(x1 - 1, y1 - len, x1, y1, argb);
    }

    // ── Промінь / блик ───────────────────────────────────────────────────

    /**
     * Вертикальний промінь, що ковзає зліва направо по області
     * {@code (x,y,w,h)}; {@code t} 0..1 — позиція, {@code band} — півширина.
     * Обрізається межами області.
     */
    public static void scanBeam(GuiGraphics g, int x, int y, int w, int h, float t, int band, int rgb, int maxAlpha) {
        int cx = Math.round(t * (w + band * 2)) - band;
        for (int i = -band; i <= band; i++) {
            int px = cx + i;
            if (px < 0 || px >= w) continue;
            float k = 1f - Math.abs(i) / (float) band;
            int a = Math.round(maxAlpha * k * k);
            if (a <= 0) continue;
            g.fill(x + px, y, x + px + 1, y + h, (a << 24) | (rgb & 0xFFFFFF));
        }
    }

    /**
     * Блик, що біжить по ЗАЛИТІЙ частині бару (як у {@code drawBarFx}, але
     * для текстурних барів): {@code filledW} — ширина заливки в px.
     */
    public static void barGlint(GuiGraphics g, int x0, int y0, int filledW, int h, long periodMs, float offset) {
        if (filledW <= 2) return;
        long now = System.currentTimeMillis();
        float sweep = (((now % periodMs) / (float) periodMs) + offset) % 1f * 1.6f - 0.3f;
        int band = Math.max(6, filledW / 9);
        int center = Math.round(sweep * filledW);
        for (int i = -band; i <= band; i++) {
            int cx = center + i;
            if (cx < 0 || cx >= filledW) continue;
            float k = 1f - Math.abs(i) / (float) band;
            int a = Math.round(0x50 * k * k);
            g.fill(x0 + cx, y0, x0 + cx + 1, y0 + h, (a << 24) | 0xFFFFFF);
        }
    }

    /** Яскрава «голівка» краю заливки + м'яке світіння перед нею. */
    public static void barHead(GuiGraphics g, int x0, int y0, int filledW, int h, int rgb, float strength) {
        if (filledW <= 0) return;
        int hx = x0 + filledW - 1;
        g.fill(hx, y0, hx + 1, y0 + h, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.55f + 0.35f * strength));
        for (int i = 1; i <= 4; i++) {
            int a = Math.round(0x50 * strength * (1f - i / 5f) * (1f - i / 5f));
            if (a <= 0) continue;
            g.fill(hx + i, y0, hx + i + 1, y0 + h, (a << 24) | (rgb & 0xFFFFFF));
        }
    }

    // ── ЕКГ ──────────────────────────────────────────────────────────────

    /**
     * ЕКГ-лінія в області {@code (x,y,w,h)}: один «удар» на повну ширину,
     * що біжить праворуч за {@code phase} (0..1). Хвіст лінії зліва
     * поступово гасне. {@code amp} 0..1 — висота піку.
     */
    public static void ecg(GuiGraphics g, int x, int y, int w, int h, float phase, int rgb, float amp, float alpha) {
        int base = y + h * 2 / 3;
        int prevY = base;
        for (int i = 0; i < w; i++) {
            float t = ((i / (float) w) + 1f - phase) % 1f;
            float v = waveform(t) * amp;
            int py = base - Math.round(v * (h * 0.62f));
            int lo = Math.min(py, prevY), hi = Math.max(py, prevY);
            float fade = (i / (float) w);
            int a = Math.round(255 * alpha * (0.15f + 0.85f * fade * fade));
            int c = (a << 24) | (rgb & 0xFFFFFF);
            g.fill(x + i, lo, x + i + 1, hi + 1, c);
            prevY = py;
        }
    }

    private static float waveform(float t) {
        if (t > 0.22f && t < 0.32f) return 0.16f * (float) Math.sin((t - 0.22f) / 0.10f * Math.PI); // P
        if (t > 0.40f && t < 0.44f) return -0.22f * (float) Math.sin((t - 0.40f) / 0.04f * Math.PI); // Q
        if (t >= 0.44f && t < 0.51f) return (float) Math.sin((t - 0.44f) / 0.07f * Math.PI);        // R
        if (t >= 0.51f && t < 0.56f) return -0.34f * (float) Math.sin((t - 0.51f) / 0.05f * Math.PI); // S
        if (t > 0.66f && t < 0.82f) return 0.26f * (float) Math.sin((t - 0.66f) / 0.16f * Math.PI); // T
        return 0f;
    }

    // ── Іскри ────────────────────────────────────────────────────────────

    /** Дрібні частинки (іскри/уламки) в локальних координатах панелі. */
    public static final class Sparks {
        private static final class P {
            float x, y, vx, vy, life, maxLife;
            int rgb;
            int size;
        }

        private final List<P> list = new ArrayList<>();
        private final int cap;

        public Sparks(int cap) { this.cap = cap; }

        public void emit(float x, float y, float vx, float vy, float life, int rgb, int size) {
            if (list.size() >= cap) return;
            P p = new P();
            p.x = x; p.y = y; p.vx = vx; p.vy = vy;
            p.life = life; p.maxLife = life; p.rgb = rgb; p.size = size;
            list.add(p);
        }

        /** Викид {@code n} іскор віялом навколо напрямку (dirX, dirY). */
        public void burst(float x, float y, int n, float speed, float dirX, float dirY, float life, int rgb) {
            for (int i = 0; i < n; i++) {
                double a = Math.atan2(dirY, dirX) + (Math.random() - 0.5) * 2.2;
                float s = speed * (0.35f + (float) Math.random() * 0.65f);
                emit(x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                    life * (0.6f + (float) Math.random() * 0.4f), rgb, Math.random() < 0.3 ? 2 : 1);
            }
        }

        public void clear() { list.clear(); }

        /** Крок фізики + малювання. {@code gravity} px/с² (додатне — вниз). */
        public void tickAndRender(GuiGraphics g, float dt, float gravity) {
            for (Iterator<P> it = list.iterator(); it.hasNext(); ) {
                P p = it.next();
                p.life -= dt;
                if (p.life <= 0f) { it.remove(); continue; }
                p.vy += gravity * dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                float k = p.life / p.maxLife;
                int a = Math.round(255 * k);
                int px = Math.round(p.x), py = Math.round(p.y);
                g.fill(px, py, px + p.size, py + p.size, (a << 24) | (p.rgb & 0xFFFFFF));
            }
        }
    }
}
