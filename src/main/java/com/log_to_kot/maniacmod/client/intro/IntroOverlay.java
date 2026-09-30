package com.log_to_kot.maniacmod.client.intro;

import com.log_to_kot.maniacmod.client.overlay.notify.RoleRevealFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.intro.IntroTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * Малює кіно-вступ: чорне покриття (затемнення/розтемнення), віньєтку й
 * loader — БЕЗ фонових плашок, лише текст з
 * контуром (на Replay-кадрі він читається завдяки контуру й letterbox).
 *
 * ── Loader (ліворуч-внизу) ───────────────────────────────────────────
 *   [спіннер]  ЗАВАНТАЖЕННЯ…        ← спіннер ЗЛІВА, текст СПРАВА від нього
 *              ПОРАДА
 *              текст поради …        ← під ними; змінюється кожні N с
 *              ▁▁▁▁▁▁▁▁ (смужка до наступної поради)
 * Позицію й розміри міняють константи нижче.
 *
 * Годинник — {@link IntroClient#tMs()}; кольори — як в оголошенні ролі
 * (виживий блакитний, маньяк червоний).
 */
@OnlyIn(Dist.CLIENT)
public final class IntroOverlay {

    // ── Розкладка (частки екрана / масштаби при висоті 540) ──────────────
    private static final float MARGIN_X = 0.055f;
    private static final float MARGIN_BOTTOM = 0.06f;
    private static final float SPINNER_SIZE = 30f;
    private static final float TITLE_SCALE = 1.7f;
    private static final float TIP_LABEL_SCALE = 0.95f;
    private static final float TIP_SCALE = 1.25f;
    private static final float TIP_MAX_WIDTH = 0.34f;   // частка ширини екрана
    private static final int SPINNER_DOTS = 12;
    private static final float SPINNER_TURNS_PER_S = 1.1f;

    // ── Таймінги loader-а ────────────────────────────────────────────────
    private static final long LOADER_IN_MS = 550;
    private static final long LOADER_OUT_MS = 450;
    private static final long TIP_IN_MS = 520;
    private static final long TIP_OUT_MS = 380;
    private static final long TIP_LINE_STAGGER_MS = 70;

    private static final int SURVIVOR_ACCENT = 0x34D7FF;
    private static final int MANIAC_ACCENT = 0xFF3B3B;

    private static final UiFx.Sparks SPARKS = new UiFx.Sparks(90);
    private static final long[] LAST_FRAME = new long[1];
    private static float emitAcc = 0f;

    private IntroOverlay() {}

    public static void render(GuiGraphics g) {
        if (!IntroClient.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float k = Math.max(0.6f, Math.min(1.8f, sh / 540f));
        float dt = UiAnim.frameDt(LAST_FRAME);

        long t = IntroClient.tMs();
        IntroClient.Stage stage = IntroClient.stage();
        int window = IntroClient.windowMs();
        int accent = IntroClient.isManiac() ? MANIAC_ACCENT : SURVIVOR_ACCENT;
        float cover = IntroClient.coverAlpha(t);
        boolean running = stage == IntroClient.Stage.RUNNING;

        // ── Віньєтка на кадрі Replay ─────────────────────────────────────
        if (running && cover < 0.98f) {
            RoleRevealFx.vignette(g, sw, sh, 0x000000, 0.6f * (1f - cover), 120);
        }
        // ── Чорне покриття ───────────────────────────────────────────────
        int coverA = Math.round(UiAnim.clamp01(cover) * 255f);
        if (coverA > 0) g.fill(0, 0, sw, sh, coverA << 24);

        // ── Loader ───────────────────────────────────────────────────────
        float anchorX = sw * MARGIN_X;
        float anchorBottom = sh - sh * MARGIN_BOTTOM;
        if (running) {
            float in = UiAnim.easeOutCubic((t - IntroTimeline.CAMERA_START_MS) / (float) LOADER_IN_MS);
            long outStart = window + IntroTimeline.DARKEN_OUT_MS - LOADER_OUT_MS;
            float out = 1f - UiAnim.easeInOutCubic((t - outStart) / (float) LOADER_OUT_MS);
            float a = UiAnim.clamp01(Math.min(in, out));
            if (a > 0.02f) drawLoader(g, mc.font, sw, sh, k, t, a, accent, anchorX, anchorBottom, dt);
        } else if (stage == IntroClient.Stage.HOLD) {
            // Довге очікування чанків: маленький спіннер, щоб екран не здавався зависшим.
            long since = t - IntroClient.blackAtMs();
            float a = UiAnim.easeOutCubic((since - 500) / 500f);
            if (a > 0.02f) {
                float s = SPINNER_SIZE * k * 0.7f;
                drawSpinner(g, anchorX + s / 2f, anchorBottom - s / 2f, s, k * 0.7f, t, accent, a * 0.8f);
            }
        } else {
            SPARKS.clear();
        }
    }

    // ── Loader ───────────────────────────────────────────────────────────

    private static void drawLoader(GuiGraphics g, Font font, int sw, int sh, float k, long t,
                                   float alpha, int accent, float x0, float bottom, float dt) {
        String title = I18n.get("maniacmod.intro.loading").toUpperCase(Locale.ROOT);
        String tipLabel = I18n.get("maniacmod.intro.tip_label").toUpperCase(Locale.ROOT);

        long local = Math.max(0L, t - IntroTimeline.CAMERA_START_MS);
        int slot = IntroClient.tipSlot(t);
        long inSlot = local - (long) slot * IntroClient.tipMs();
        int lastSlot = (IntroClient.windowMs() - 1) / IntroClient.tipMs();
        String tip = IntroClient.tips().get(slot);

        // Розкладка порад: рядки рахуємо наперед, щоб блок став НИЖНІМ краєм на якорі.
        float tipScale = TIP_SCALE * k;
        int wrapW = Math.max(60, Math.round(sw * TIP_MAX_WIDTH / tipScale));
        List<FormattedCharSequence> lines = tip.isEmpty()
            ? List.of() : font.split(Component.literal(tip), wrapW);
        int shown = Math.min(lines.size(), 4);

        float spinner = SPINNER_SIZE * k;
        float rowGap = 12f * k;
        float labelH = font.lineHeight * TIP_LABEL_SCALE * k;
        float lineH = (font.lineHeight + 2) * tipScale;
        float barGap = 9f * k;
        float tipBlockH = tip.isEmpty() ? 0f : labelH + 4f * k + shown * lineH + barGap + 2f * k;
        float top = bottom - (spinner + (tipBlockH > 0 ? rowGap + tipBlockH : 0f));

        // ── Ряд: спіннер зліва, текст завантаження праворуч від нього ────
        float rowCy = top + spinner / 2f;
        drawSpinner(g, x0 + spinner / 2f, rowCy, spinner, k, t, accent, alpha);

        float titleScale = TITLE_SCALE * k;
        float tx = x0 + spinner + 14f * k;
        float ty = rowCy - font.lineHeight * titleScale / 2f;
        float cursor = 0f;
        int n = title.length();
        float sweep = ((t % 1700L) / 1700f) * (n + 8) - 4f;
        for (int i = 0; i < n; i++) {
            String ch = String.valueOf(title.charAt(i));
            float w = font.width(ch);
            float slide = UiAnim.easeOutCubic((local - i * 40L) / 420f);
            float br = Math.max(0f, 1f - Math.abs(i - sweep) / 3.2f);
            int rgb = lerpRgb(0xE8ECF4, accent, br * 0.9f);
            drawOutlined(g, font, ch, tx + cursor * titleScale, ty + (1f - slide) * 6f * k,
                titleScale, rgb, alpha * slide);
            cursor += w + 1.4f;
        }
        // Крапки «…»: 0–3, рахують час.
        int dots = (int) ((t / 380L) % 4L);
        for (int d = 0; d < 3; d++) {
            if (d >= dots) break;
            drawOutlined(g, font, ".", tx + (cursor + d * 3.2f) * titleScale, ty, titleScale, accent, alpha);
        }

        // ── Порада під рядком ────────────────────────────────────────────
        if (!tip.isEmpty()) {
            float y = top + spinner + rowGap;
            float tipIn = UiAnim.clamp01(inSlot / (float) TIP_IN_MS);
            float tipOut = slot < lastSlot
                ? UiAnim.clamp01((inSlot - (IntroClient.tipMs() - TIP_OUT_MS)) / (float) TIP_OUT_MS) : 0f;
            float outE = UiAnim.easeInOutCubic(tipOut);

            // Підпис «ПОРАДА» — акцентний, зі світлою рискою зліва.
            float la = alpha * UiAnim.easeOutCubic(tipIn) * (1f - outE);
            g.fill(Math.round(x0), Math.round(y + 1), Math.round(x0 + 2 * k), Math.round(y + labelH), argb(accent, la));
            drawOutlined(g, font, tipLabel, x0 + 8f * k, y + 1f, TIP_LABEL_SCALE * k, accent, la);
            y += labelH + 4f * k;

            for (int i = 0; i < shown; i++) {
                float p = UiAnim.easeOutCubic((inSlot - i * TIP_LINE_STAGGER_MS) / (float) TIP_IN_MS);
                float a = alpha * p * (1f - outE);
                float off = (1f - p) * 12f * k - outE * 9f * k;
                drawOutlinedSeq(g, font, lines.get(i), x0 + 8f * k, y + off, tipScale, 0xF2F5FB, a);
                y += lineH;
            }

            // Смужка часу до наступної поради (лише поки є наступна).
            y += barGap;
            float barW = Math.min(sw * TIP_MAX_WIDTH, 240f * k);
            float frac = slot < lastSlot ? UiAnim.clamp01(inSlot / (float) IntroClient.tipMs()) : 1f;
            int bx = Math.round(x0 + 8f * k), by = Math.round(y), bw = Math.round(barW);
            g.fill(bx, by, bx + bw, by + Math.max(1, Math.round(1.5f * k)), argb(0xFFFFFF, alpha * 0.14f));
            int fw = Math.round(bw * frac);
            if (fw > 0) {
                g.fill(bx, by, bx + fw, by + Math.max(1, Math.round(1.5f * k)), argb(accent, alpha * 0.85f));
                UiFx.barHead(g, bx, by, fw, Math.max(1, Math.round(1.5f * k)), accent, alpha);
            }
        }

        // ── Іскри навколо спіннера ───────────────────────────────────────
        emitAcc += dt * 9f;
        int guard = 3;
        while (emitAcc >= 1f && guard-- > 0) {
            emitAcc -= 1f;
            double ang = Math.random() * Math.PI * 2;
            float r = spinner * 0.55f;
            SPARKS.emit(x0 + spinner / 2f + (float) Math.cos(ang) * r, rowCy + (float) Math.sin(ang) * r,
                (float) (Math.random() - 0.5) * 10f, -6f - (float) Math.random() * 14f,
                0.9f + (float) Math.random() * 0.8f, accent, 1);
        }
        if (emitAcc >= 1f) emitAcc = 0f;
        SPARKS.tickAndRender(g, dt, 0f);
    }

    /** Спіннер: обертовий «хвіст» із 12 точок + тонке кільце + зворотна дуга. Центр (cx, cy), діаметр size. */
    private static void drawSpinner(GuiGraphics g, float cx, float cy, float size, float k,
                                    long t, int accent, float alpha) {
        float r = size * 0.42f;
        float head = ((t / 1000f) * SPINNER_TURNS_PER_S % 1f) * SPINNER_DOTS;
        float pulse = 1f + 0.05f * (float) Math.sin(t / 260.0);

        // Слабке зовнішнє кільце.
        int ringN = 36;
        for (int i = 0; i < ringN; i++) {
            double a = i * (Math.PI * 2 / ringN);
            dot(g, cx + (float) Math.cos(a) * r * 1.32f * pulse, cy + (float) Math.sin(a) * r * 1.32f * pulse,
                1f, 0xFFFFFF, alpha * 0.10f);
        }
        // Основний хвіст.
        for (int i = 0; i < SPINNER_DOTS; i++) {
            float d = ((head - i) % SPINNER_DOTS + SPINNER_DOTS) % SPINNER_DOTS;
            float f = 1f - d / SPINNER_DOTS;
            float a = f * f;
            double ang = i * (Math.PI * 2 / SPINNER_DOTS) - Math.PI / 2;
            float px = cx + (float) Math.cos(ang) * r * pulse;
            float py = cy + (float) Math.sin(ang) * r * pulse;
            float s = (1.6f + 2.6f * a) * k;
            if (a > 0.6f) dot(g, px, py, s * 1.9f, accent, alpha * (a - 0.6f) * 0.5f); // світіння голови
            dot(g, px, py, s, lerpRgb(0xFFFFFF, accent, 1f - a * 0.5f), alpha * (0.15f + 0.85f * a));
        }
        // Внутрішня зворотна дуга з трьох точок.
        float inner = -((t / 1000f) * 0.7f) * (float) (Math.PI * 2);
        for (int i = 0; i < 3; i++) {
            double ang = inner + i * 0.5;
            dot(g, cx + (float) Math.cos(ang) * r * 0.5f, cy + (float) Math.sin(ang) * r * 0.5f,
                (1.2f + i * 0.5f) * k, accent, alpha * (0.35f + 0.2f * i));
        }
    }

    // ── Примітиви ────────────────────────────────────────────────────────

    private static void dot(GuiGraphics g, float x, float y, float size, int rgb, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        if (a < 4) return;
        int s = Math.max(1, Math.round(size));
        int x0 = Math.round(x - s / 2f), y0 = Math.round(y - s / 2f);
        g.fill(x0, y0, x0 + s, y0 + s, (a << 24) | (rgb & 0xFFFFFF));
    }

    /** Текст із чорним контуром (без плашки): (x, y) — лівий верх, екранні одиниці. */
    private static void drawOutlined(GuiGraphics g, Font font, String text, float x, float y,
                                     float scale, int rgb, float alpha) {
        if (alpha < 0.03f || text.isEmpty()) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        int oc = argb(0x000000, alpha * 0.75f);
        g.drawString(font, text, -1, 0, oc, false);
        g.drawString(font, text, 1, 0, oc, false);
        g.drawString(font, text, 0, -1, oc, false);
        g.drawString(font, text, 0, 1, oc, false);
        g.drawString(font, text, 0, 0, argb(rgb, alpha), false);
        pose.popPose();
    }

    private static void drawOutlinedSeq(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y,
                                        float scale, int rgb, float alpha) {
        if (alpha < 0.03f) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        int oc = argb(0x000000, alpha * 0.75f);
        g.drawString(font, text, -1, 0, oc, false);
        g.drawString(font, text, 1, 0, oc, false);
        g.drawString(font, text, 0, -1, oc, false);
        g.drawString(font, text, 0, 1, oc, false);
        g.drawString(font, text, 0, 0, argb(rgb, alpha), false);
        pose.popPose();
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static int lerpRgb(int a, int b, float t) {
        float k = UiAnim.clamp01(t);
        int r = Math.round(((a >> 16) & 0xFF) * (1f - k) + ((b >> 16) & 0xFF) * k);
        int gr = Math.round(((a >> 8) & 0xFF) * (1f - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1f - k) + (b & 0xFF) * k);
        return (r << 16) | (gr << 8) | bl;
    }
}
