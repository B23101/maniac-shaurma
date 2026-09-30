package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Дрібні безстанові помічники tab-екрана: текст із масштабом, «розшифровка»
 * символів, таймлайн-стадії, кольори. Усе, що потрібно кільком карткам і
 * не належить жодній із них.
 *
 * ── Про альфу тексту ─────────────────────────────────────────────────
 * {@code GuiGraphics.drawString} вважає колір з альфою &lt; 4 (з 255)
 * «забутою» і робить його ПОВНІСТЮ непрозорим. Тому анімація появи, що
 * веде альфу в нуль, без захисту дала б блимання: в останні кадри
 * згасання текст раптом спалахував би. Усі текстові методи тут просто
 * нічого не малюють, коли альфа нижча за {@link #MIN_TEXT_ALPHA}.
 */
final class TabFx {

    private TabFx() {}

    static final int MIN_TEXT_ALPHA = 6;

    private static final String GLYPHS = "#%&@$*+=?/\\|<>0123456789";

    // ── Кольори ──────────────────────────────────────────────────────

    static int a(int argb, float k) {
        return GeneratorFuelUiTheme.alpha(argb, k);
    }

    static int mix(int from, int to, float t) {
        return GeneratorFuelUiTheme.mix(from, to, t);
    }

    // ── Таймлайн ─────────────────────────────────────────────────────

    /** Прогрес стадії 0..1, що стартує в {@code delay} с і триває {@code dur} с (лінійний). */
    static float stage(float t, float delay, float dur) {
        return UiAnim.clamp01((t - delay) / dur);
    }

    /** Те саме, але з ease-out — для виїзду елементів. */
    static float stageOut(float t, float delay, float dur) {
        return UiAnim.easeOutCubic(stage(t, delay, dur));
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float pulse(long nowMs, float periodMs) {
        return 0.5f + 0.5f * (float) Math.sin(nowMs / periodMs * Math.PI * 2.0);
    }

    // ── Текст ────────────────────────────────────────────────────────

    static void text(GuiGraphics g, Font font, String s, float x, float y, float scale, int argb) {
        if (s == null || s.isEmpty() || (argb >>> 24) < MIN_TEXT_ALPHA) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, argb, false);
        pose.popPose();
    }

    static void textShadow(GuiGraphics g, Font font, String s, float x, float y, float scale, int argb) {
        if (s == null || s.isEmpty() || (argb >>> 24) < MIN_TEXT_ALPHA) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, argb, true);
        pose.popPose();
    }

    static int width(Font font, String s, float scale) {
        return Math.round(font.width(s) * scale);
    }

    static void textCentered(GuiGraphics g, Font font, String s, float cx, float y, float scale, int argb) {
        text(g, font, s, cx - width(font, s, scale) / 2f, y, scale, argb);
    }

    static void textRight(GuiGraphics g, Font font, String s, float right, float y, float scale, int argb) {
        text(g, font, s, right - width(font, s, scale), y, scale, argb);
    }

    /** Обрізає рядок під ширину (з урахуванням масштабу), додаючи «..». */
    static String clip(Font font, String s, int maxWidthPx, float scale) {
        int max = Math.round(maxWidthPx / scale);
        if (font.width(s) <= max) return s;
        int dots = font.width("..");
        return font.plainSubstrByWidth(s, Math.max(0, max - dots)) + "..";
    }

    /**
     * «Розшифровка»: перші {@code progress·довжина} символів уже справжні,
     * решта — мерехтливі знаки. {@code seed} розводить рядки, щоб вони не
     * миготіли синхронно.
     */
    static String scramble(String target, float progress, int seed, long nowMs) {
        if (target == null || target.isEmpty() || progress >= 1f) return target;
        int reveal = (int) (target.length() * Math.max(0f, progress));
        long frame = nowMs / 45L;
        StringBuilder sb = new StringBuilder(target.length());
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (i < reveal || c == ' ') {
                sb.append(c);
            } else {
                long h = frame * 31L + i * 17L + seed * 13L;
                h ^= (h >>> 3);
                sb.append(GLYPHS.charAt((int) Math.floorMod(h, (long) GLYPHS.length())));
            }
        }
        return sb.toString();
    }

    static String mmss(long millis) {
        long s = Math.max(0L, millis / 1000L);
        return (s / 60L) + ":" + (s % 60L < 10 ? "0" : "") + (s % 60L);
    }

    // ── Форми ────────────────────────────────────────────────────────

    /** Діагональна штриховка «небезпека»: анімована зсувом {@code phase}. */
    static void hazard(GuiGraphics g, int x, int y, int w, int h, int argb, int step, int phase) {
        if ((argb >>> 24) == 0) return;
        for (int row = 0; row < h; row++) {
            int off = Math.floorMod(row + phase, step);
            for (int xx = off; xx < w; xx += step) {
                int len = Math.min(step / 2, w - xx);
                g.fill(x + xx, y + row, x + xx + len, y + row + 1, argb);
            }
        }
    }

    /** Внутрішня «заглиблена» рамка панелі-картки. */
    static void card(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, edge);
        g.fill(x, y + h - 1, x + w, y + h, edge);
        g.fill(x, y, x + 1, y + h, edge);
        g.fill(x + w - 1, y, x + w, y + h, edge);
    }

    /** Скан-лінії поверх області (для 3D-в'юпортів — оживляє модель). */
    static void scanlines(GuiGraphics g, int x, int y, int w, int h, int argb, int step) {
        if ((argb >>> 24) == 0) return;
        for (int yy = y; yy < y + h; yy += step) {
            g.fill(x, yy, x + w, yy + 1, argb);
        }
    }
}
