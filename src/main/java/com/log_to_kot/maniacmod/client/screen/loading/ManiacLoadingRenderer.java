package com.log_to_kot.maniacmod.client.screen.loading;

import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Єдина сцена екрана заходу — замість ванільних «Підключення до сервера…»,
 * «Завантаження місцевості…» і «Підготовка місця появи: N%».
 *
 * ── Що на екрані ─────────────────────────────────────────────────────
 * • кінематографічні смуги (letterbox), що виїжджають зверху й знизу;
 * • назва мода літерами, що падають і відскакують, з кровʼю і рідкісним
 *   коротким глітчем (RGB-розкол ≤3 px, без стробування);
 * • картка сервера/світу + «сигнал», що наростає разом із зʼєднанням;
 * • гачок на ланцюгу, що розгойдується в конусі хитної лампи, пил у
 *   промені, калюжа світла, крапля крові на вістрі;
 * • туман, жарини, скан-лінії; серцебиття з HorrorBackdrop керує пульсом;
 * • смуга прогресу з вузлами-кроками: пройдені/активний/майбутні;
 * • відсотки, порада (із {@code maniacmod.intro.tip.N}), кнопка «Скасувати».
 *
 * ── Принципи ─────────────────────────────────────────────────────────
 * Без текстур — лише {@code fill}/{@code fillGradient}, як і решта UI мода.
 * Жодного різкого мерехтіння: світло лампи просідає на ~0.2 с раз на 9 с,
 * решта рухається плавними синусоїдами. Клас безстановий, увесь стан —
 * у {@link ManiacLoadingSession}.
 *
 * ── Відмовостійкість ─────────────────────────────────────────────────
 * Якщо малювання кине виняток, {@link #renderScreen} повертає {@code false}
 * і назавжди (до перезапуску гри) віддає екран ванілі — краще звичайний
 * «Loading terrain», ніж креш під час заходу.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacLoadingRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int DIM = 0xFF7A7A7A;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;
    private static final int STEEL = 0xFF23282D;
    private static final int STEEL_LIT = 0xFFA9B3C4;
    private static final int LIGHT = 0xFFFFE9CF;

    private static final int CANCEL_W = 96;

    private static boolean failed;
    private static int loggedKinds;

    private ManiacLoadingRenderer() {}

    // ═══════════════ Вхід із міксинів ════════════════════════════════

    /** @return {@code true}, якщо екран намальовано нами й ванільний {@code render} треба скасувати. */
    public static boolean renderScreen(GuiGraphics g, Screen screen, ManiacLoadingSession.Kind kind, int mx, int my) {
        if (failed) return false;
        try {
            if ((loggedKinds & (1 << kind.ordinal())) == 0) {
                loggedKinds |= 1 << kind.ordinal();
                LOGGER.info("[ManiacMod] екран заходу: перший кадр {} ({}x{})", kind, screen.width, screen.height);
            }
            ManiacLoadingSession s = ManiacLoadingSession.frame(kind);
            render(g, screen.width, screen.height, s, mx, my, findCancel(screen), false);
            return true;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[ManiacMod] екран заходу впав, повертаю ванільний", e);
            return false;
        }
    }

    /** Ванільна кнопка «Скасувати» живе в екрані (клік обробляє ванілі), ми лише переносимо й перемальовуємо її. */
    public static AbstractWidget findCancel(Screen screen) {
        for (GuiEventListener l : screen.children()) {
            if (l instanceof AbstractWidget w) return w;
        }
        return null;
    }

    public static void placeCancel(Screen screen) {
        AbstractWidget w = findCancel(screen);
        if (w == null) return;
        int lb = Math.round(screen.height * 0.075f);
        int lm = leftMargin(screen.width);
        w.setWidth(CANCEL_W);
        w.setX(screen.width - lm - CANCEL_W);
        w.setY(lb + 8);
    }

    // ═══════════════ Сцена ═══════════════════════════════════════════

    /**
     * Малює всю сцену. {@code exit = true} — режим оверлея виходу: усі кроки
     * пройдено, кнопки нема, статус «Готово».
     */
    static void render(GuiGraphics g, int w, int h, ManiacLoadingSession s,
                       int mx, int my, AbstractWidget cancel, boolean exit) {
        Font f = Minecraft.getInstance().font;
        long now = Util.getMillis();
        long age = now - s.startedAt;
        float t = age / 1000f;
        float beat = s.backdrop.beat(now);

        int lb = Math.round(h * 0.075f * UiAnim.easeOutCubic(age / 800f));
        boolean tipsOn = h >= 300 && !s.tips.isEmpty();
        int blockH = tipsOn ? 88 : 50;
        int blockBottom = h - lb - 10;
        int blockTop = blockBottom - blockH;
        int lm = leftMargin(w);

        g.fill(0, 0, w, h, 0xFF050506);
        s.backdrop.render(g, w, h, 1f);
        drawFog(g, w, h, now);
        drawEmbers(g, w, h, now);
        if (w >= 360) drawLampScene(g, w, lb, blockTop, t, beat);
        drawScanlines(g, w, h);
        drawLetterbox(g, w, h, lb, now, beat);

        int titleBottom = drawTitleBlock(g, f, w, h, s, lb, lm, age, now, exit);
        drawServerCard(g, f, s, lm, titleBottom + 16, blockTop - 8, w, age, now);
        drawProgressBlock(g, f, s, lm, w - lm * 2, blockTop, tipsOn, age, now, beat, exit);

        if (cancel != null && !exit) drawCancel(g, f, s, cancel, mx, my, age);

        // плавний вихід із чорного на самому початку: спершу темрява, потім сцена (без стрибка)
        float black = 1f - UiAnim.easeInOutCubic(age / 1000f);
        if (black > 0.01f) g.fill(0, 0, w, h, BloodFx.a(0xFF000000, black));
    }

    private static int leftMargin(int w) {
        return Math.max(24, (int) (w * 0.07f));
    }

    // ═══════════════ Фон ═════════════════════════════════════════════

    /** Три повільні смуги туману; дрейфують по вертикалі, без жодних різких змін. */
    private static void drawFog(GuiGraphics g, int w, int h, long now) {
        for (int i = 0; i < 3; i++) {
            float ph = now / (9000f + i * 3500f) + i * 1.7f;
            float cy = h * (0.30f + 0.22f * i) + (float) Math.sin(ph * 6.283f) * h * 0.04f;
            int half = (int) (h * 0.11f);
            int col = (i == 1) ? 0xFF3A0A10 : 0xFF1C2024;
            float al = 0.07f + 0.025f * i;
            int top = Math.round(cy) - half;
            int mid = Math.round(cy);
            int bot = Math.round(cy) + half;
            g.fillGradient(0, top, w, mid, BloodFx.a(col, 0f), BloodFx.a(col, al));
            g.fillGradient(0, mid, w, bot, BloodFx.a(col, al), BloodFx.a(col, 0f));
        }
    }

    /** Жарини, що повільно здіймаються вгору; позиція — чиста функція часу. */
    private static void drawEmbers(GuiGraphics g, int w, int h, long now) {
        float sec = now / 1000f;
        for (int i = 0; i < 34; i++) {
            long seed = i * 131L + 7L;
            float x = BloodFx.hash01(seed) * w + (float) Math.sin(sec * 0.4f + seed) * 6f;
            float speed = 6f + BloodFx.hash01(seed + 1) * 14f;
            float y = h - ((sec * speed + BloodFx.hash01(seed + 2) * h) % h);
            float life = y / h;
            float tw = 0.6f + 0.4f * (float) Math.sin(sec * 2.2f + i);
            float a = (float) Math.sin(Math.PI * life) * 0.55f * tw;
            if (a < 0.03f) continue;
            int col = (i % 3 == 0) ? 0xFFFF6A3A : 0xFFB0242F;
            int sz = (i % 5 == 0) ? 2 : 1;
            int ix = Math.round(x);
            int iy = Math.round(y);
            g.fill(ix, iy, ix + sz, iy + sz, BloodFx.a(col, a));
        }
    }

    private static void drawScanlines(GuiGraphics g, int w, int h) {
        int c = 0x12000000;
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, c);
    }

    private static void drawLetterbox(GuiGraphics g, int w, int h, int lb, long now, float beat) {
        if (lb <= 0) return;
        g.fill(0, 0, w, lb, 0xFF000000);
        g.fill(0, h - lb, w, h, 0xFF000000);
        int line = BloodFx.a(BloodFx.MID, 0.55f + 0.30f * beat);
        g.fill(0, lb, w, lb + 1, line);
        g.fill(0, h - lb - 1, w, h - lb, line);
        // яскрава «іскра» ковзає по внутрішніх краях
        float p = (now % 5200L) / 5200f;
        int sx = Math.round(p * (w + 120)) - 60;
        for (int i = -30; i <= 30; i++) {
            int x = sx + i;
            if (x < 0 || x >= w) continue;
            float k = 1f - Math.abs(i) / 30f;
            int col = BloodFx.a(BloodFx.HOT, 0.5f * k * k);
            g.fill(x, lb, x + 1, lb + 1, col);
            g.fill(w - 1 - x, h - lb - 1, w - x, h - lb, col);
        }
    }

    // ═══════════════ Сцена з гачком ══════════════════════════════════

    private static void drawLampScene(GuiGraphics g, int w, int lb, int floorY, float t, float beat) {
        float lampX = w * 0.80f;
        float hookX = w * 0.72f;
        int top = 0;
        float coneLen = floorY - 6 - top;
        if (coneLen < 60f) return;
        float coneHalf = Math.min(w * 0.17f, 74f);

        // світло просідає раз на ~9 с на ~0.2 с, решта — легке дихання від пульсу
        float x9 = t % 9.0f;
        float dip = x9 < 0.22f ? 0.5f + 0.5f * (float) Math.sin(x9 * 62f) : 0f;
        float flick = Mth.clamp(0.92f + 0.08f * beat - 0.40f * dip, 0.3f, 1f);

        float aL = 3.2f * (float) Math.sin(t * 0.7f) + 1.1f * (float) Math.sin(t * 1.3f + 0.8f);
        float aH = 6.5f * (float) Math.sin(t * 0.82f + 0.4f) + 2.0f * (float) Math.sin(t * 1.9f);

        drawLamp(g, lampX, top, lb, coneLen, coneHalf, aL, t, flick);
        drawLightPool(g, lampX, aL, coneLen, coneHalf, floorY - 6, flick);
        drawHook(g, hookX, top, coneLen * 0.56f, aH, lampX, aL, coneLen, coneHalf, t, flick);
    }

    private static void drawLamp(GuiGraphics g, float lampX, int top, int lb,
                                 float coneLen, float coneHalf, float aL, float t, float flick) {
        int chain = lb + 16;
        g.pose().pushPose();
        g.pose().translate(lampX, top, 0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(aL));

        for (int y = 0; y < chain - 4; y += 4) g.fill(-1, y, 1, y + 3, 0xFF2A2F34);
        g.fill(-9, chain - 4, 9, chain + 2, 0xFF14171A);
        g.fill(-9, chain - 4, 9, chain - 3, 0xFF2E343A);

        // конус: три шари — зовнішній м'який, основний, яскраве ядро
        int y0 = chain + 2;
        for (int y = y0; y < coneLen; y += 3) {
            float k = (y - y0) / (coneLen - y0);
            int hw = Math.round(5f + k * coneHalf);
            float fall = (float) Math.pow(1f - k, 1.3f);
            g.fill(-Math.round(hw * 1.15f), y, Math.round(hw * 1.15f), y + 3, BloodFx.a(LIGHT, 0.045f * fall * flick));
            g.fill(-hw, y, hw, y + 3, BloodFx.a(LIGHT, 0.085f * fall * flick));
            int core = Math.round(hw * 0.5f);
            g.fill(-core, y, core, y + 3, BloodFx.a(LIGHT, 0.07f * fall * flick));
        }

        // пил у промені: дрейфує вниз і гойдається вбік, мерехтить плавно
        float span = coneLen - y0;
        for (int i = 0; i < 24; i++) {
            float u = BloodFx.hash01(i * 17L + 3L);
            float v = BloodFx.hash01(i * 31L + 5L);
            float y = y0 + ((v * span + t * (5f + 7f * u)) % span);
            float k = (y - y0) / span;
            float hw = 5f + k * coneHalf;
            float x = (float) Math.sin(t * 0.35f + i * 2.1f) * hw * 0.8f;
            float a = 0.6f * (float) Math.sin(Math.PI * k) * (0.5f + 0.5f * (float) Math.sin(t * 2f + i)) * flick;
            if (a < 0.04f) continue;
            int px = Math.round(x);
            int py = Math.round(y);
            g.fill(px, py, px + 1, py + 1, BloodFx.a(0xFFFFF0D8, a));
        }

        // лампочка
        BloodFx.blob(g, 0f, chain + 4f, 9f, BloodFx.a(LIGHT, 0.10f * flick));
        BloodFx.blob(g, 0f, chain + 4f, 4f, BloodFx.a(LIGHT, 0.35f * flick));
        g.fill(-4, chain + 2, 4, chain + 4, BloodFx.a(0xFFFFFFFF, 0.55f + 0.4f * flick));
        g.pose().popPose();
    }

    private static void drawLightPool(GuiGraphics g, float lampX, float aL, float coneLen,
                                      float coneHalf, int floorY, float flick) {
        float cx = lampX - coneLen * (float) Math.sin(Math.toRadians(aL));
        float pw = coneHalf * 1.25f + 10f;
        for (int dy = -6; dy <= 6; dy++) {
            float k = 1f - (dy * dy) / 36f;
            int half = Math.round(pw * (float) Math.sqrt(Math.max(0f, k)));
            if (half <= 0) continue;
            float a = 0.16f * k * flick;
            g.fill(Math.round(cx) - half, floorY + dy, Math.round(cx) + half, floorY + dy + 1, BloodFx.a(LIGHT, a));
        }
    }

    /** Освітленість точки світу конусом лампи (0..1) — для підсвітки металу. */
    private static float lit(float wx, float wy, float lampX, float aL, float coneLen, float coneHalf, float flick) {
        float axis = lampX - wy * (float) Math.sin(Math.toRadians(aL));
        float k = Mth.clamp(wy / coneLen, 0f, 1f);
        float hw = 5f + k * coneHalf;
        float side = Mth.clamp(1f - Math.abs(wx - axis) / (hw + 8f), 0f, 1f);
        return side * Mth.clamp(1.15f - k, 0f, 1f) * flick;
    }

    private static void drawHook(GuiGraphics g, float hookX, int top, float chainLen, float aH,
                                 float lampX, float aL, float coneLen, float coneHalf, float t, float flick) {
        float sinH = (float) Math.sin(Math.toRadians(aH));
        g.pose().pushPose();
        g.pose().translate(hookX, top, 0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(aH));

        // ланцюг: чергування «в профіль» і «в анфас»
        int idx = 0;
        for (int y = 0; y < chainLen; y += 5, idx++) {
            float l = lit(hookX - y * sinH, y, lampX, aL, coneLen, coneHalf, flick);
            int col = BloodFx.mix(STEEL, STEEL_LIT, l);
            if ((idx & 1) == 0) {
                g.fill(-2, y, 2, y + 5, col);
                g.fill(-1, y + 1, 1, y + 4, 0xFF07080A);
            } else {
                g.fill(-1, y, 1, y + 6, col);
            }
        }

        float ly = chainLen;
        g.pose().translate(0f, ly, 0f);

        float hl = lit(hookX - ly * sinH, ly, lampX, aL, coneLen, coneHalf, flick);
        int steel = BloodFx.mix(STEEL, STEEL_LIT, hl);
        // вушко
        BloodFx.border1(g, -3, 0, 7, 7, steel);
        // стрижень
        g.fill(-2, 7, 2, 22, steel);
        g.fill(-2, 7, -1, 22, BloodFx.a(0xFFFFFFFF, 0.25f * hl));
        // вигин: дуга радіуса 7 від (0,22) вниз-вправо-вгору, вістря звужується
        float cxArc = 7f;
        float cyArc = 22f;
        float tipX = 0f;
        float tipY = 0f;
        for (int deg = 0; deg <= 210; deg += 14) {
            double r = Math.toRadians(deg);
            float px = cxArc - 7f * (float) Math.cos(r);
            float py = cyArc + 7f * (float) Math.sin(r);
            int sz = deg > 180 ? 2 : 3;
            int ix = Math.round(px) - 1;
            int iy = Math.round(py) - 1;
            g.fill(ix, iy, ix + sz, iy + sz, steel);
            tipX = px;
            tipY = py;
        }

        // крапля крові на вістрі: набрякає ~55 % циклу, далі падає й гасне
        float ph = (t % 3.4f) / 3.4f;
        if (ph < 0.55f) {
            float r = 0.6f + 1.8f * (ph / 0.55f);
            BloodFx.blob(g, tipX, tipY + 2f + r * 0.5f, r, BloodFx.a(BloodFx.BRIGHT, 0.95f));
        } else {
            float fk = (ph - 0.55f) / 0.45f;
            float fy = tipY + 3f + fk * fk * 70f;
            BloodFx.blob(g, tipX, fy, 1.5f, BloodFx.a(BloodFx.BRIGHT, 1f - 0.85f * fk * fk));
        }
        g.pose().popPose();
    }

    // ═══════════════ Назва ═══════════════════════════════════════════

    /** @return Y під блоком назви (під підписом). */
    private static int drawTitleBlock(GuiGraphics g, Font f, int w, int h, ManiacLoadingSession s,
                                      int lb, int lm, long age, long now, boolean exit) {
        float ts = Mth.clamp(h / 90f, 3f, 6f);
        int overlineY = lb + Math.max(10, (int) (h * 0.12f));
        int titleY = overlineY + 14;
        int subY = titleY + (int) (9 * ts) + 14;

        // надрядок: червона цятка + «ЗАХІД НА СЕРВЕР»
        float over = UiAnim.clamp01((age - 150L) / 450f);
        float beat = s.backdrop.beat(now);
        BloodFx.blob(g, lm + 2.5f, overlineY + 4f, 2.2f + beat * 0.8f, BloodFx.a(BloodFx.BRIGHT, over));
        text(g, f, I18n.get(s.solo ? "maniacmod.loading.overline.solo" : "maniacmod.loading.overline.online"),
            lm + 9, overlineY, 0.85f, BloodFx.a(BODY, over), false);

        String title = I18n.get("maniacmod.esc.brand.title");
        float gap = 3f;
        float totalW = 0f;
        for (int i = 0; i < title.length(); i++) {
            totalW += f.width(String.valueOf(title.charAt(i))) * ts;
            if (i < title.length() - 1) totalW += gap;
        }

        long landed = 250L + title.length() * 85L + 520L;
        float glitch = age > landed + 400L ? glitchAmount(now) : 0f;
        int jitterIdx = glitch > 0f ? (int) ((now / 60L) % title.length()) : -1;

        float cx = lm;
        for (int i = 0; i < title.length(); i++) {
            String ch = String.valueOf(title.charAt(i));
            float cw = f.width(ch) * ts;
            float tt = UiAnim.clamp01((age - 250L - i * 85L) / 520f);
            float yOff = -(1f - bounce(tt)) * 90f;
            float alpha = UiAnim.clamp01(tt * 3f);
            if (alpha > 0.02f) {
                float jy = (i == jitterIdx) ? (now % 2 == 0 ? -2f : 2f) * glitch : 0f;
                float gy = titleY + yOff + jy;
                if (glitch > 0f) {
                    int off = Math.max(1, Math.round(3f * glitch));
                    glyph(g, f, ch, cx - off, gy, ts, BloodFx.a(0xFFFF2A38, 0.55f * glitch * alpha));
                    glyph(g, f, ch, cx + off, gy, ts, BloodFx.a(0xFFB8C8E8, 0.35f * glitch * alpha));
                }
                glyph(g, f, ch, cx + 2, gy + 2, ts, BloodFx.a(BloodFx.MID, alpha));
                glyph(g, f, ch, cx, gy, ts, BloodFx.a(SILVER, alpha));
            }
            cx += cw + gap;
        }

        // підкреслення з бігучим бліком
        long lineStart = landed - 200L;
        if (age > lineStart) {
            float k = UiAnim.easeOutCubic((age - lineStart) / 700f);
            int lineY = titleY + (int) (9 * ts) + 5;
            int len = Math.round(totalW * k);
            g.fill(lm, lineY, lm + len, lineY + 1, BloodFx.a(BloodFx.MID, 0.9f));
            int hot = Math.round(((now / 14L) % (long) (totalW + 60f))) - 30;
            for (int i = 0; i < 30; i++) {
                int x = lm + hot + i;
                if (x < lm || x >= lm + len) continue;
                float kk = 1f - Math.abs(i - 15) / 15f;
                g.fill(x, lineY, x + 1, lineY + 1, BloodFx.a(BloodFx.HOT, kk));
            }
        }

        // кров стікає з літер
        if (age > landed) {
            int drips = Math.max(5, (int) (totalW / 22f));
            int y0 = titleY + (int) (7.4f * ts);
            for (int k = 0; k < drips; k++) {
                float sd = BloodFx.hash01(1000L + k * 77L);
                float grow = UiAnim.easeOutCubic((age - landed - k * 140L) / 900f);
                if (grow <= 0f) continue;
                float len = (8f + sd * 26f) * grow + 1.4f * (float) Math.sin(now / 800.0 + k) - 0.5f;
                int x = lm + 2 + Math.round((k + 0.25f + sd * 0.5f) * (totalW - 6f) / drips);
                BloodFx.drip(g, x, y0, 2 + (k % 2), len, now, sd, 1f);
            }
        }

        text(g, f, I18n.get("maniacmod.esc.brand.sub"), lm, subY, 0.9f,
            BloodFx.a(BODY, UiAnim.clamp01((age - 900L) / 500f)), false);
        return subY + 10;
    }

    /** 0..1 — короткий глітч (≈220 мс) раз на ~6.7 с; плавні фронти, без стробування. */
    private static float glitchAmount(long now) {
        long period = 6700L;
        long x = now % period;
        if (x > 220L) return 0f;
        return (float) Math.sin(Math.PI * x / 220.0);
    }

    private static float bounce(float t) {
        float n = 7.5625f;
        float d = 2.75f;
        if (t < 1f / d) return n * t * t;
        if (t < 2f / d) { t -= 1.5f / d; return n * t * t + 0.75f; }
        if (t < 2.5f / d) { t -= 2.25f / d; return n * t * t + 0.9375f; }
        t -= 2.625f / d;
        return n * t * t + 0.984375f;
    }

    // ═══════════════ Картка сервера ══════════════════════════════════

    private static void drawServerCard(GuiGraphics g, Font f, ManiacLoadingSession s, int x, int y,
                                       int maxBottom, int w, long age, long now) {
        int ch = 40;
        if (y + ch > maxBottom) return;
        int cw = Mth.clamp((int) (w * 0.36f), 150, 240);
        float a = UiAnim.easeOutCubic((age - 1100L) / 500f);
        if (a <= 0.01f) return;

        int slide = Math.round((1f - a) * -18f);
        int cx = x + slide;
        g.fill(cx, y, cx + cw, y + ch, BloodFx.a(PANEL, 0.92f * a));
        BloodFx.border1(g, cx, y, cw, ch, BloodFx.a(BORDER, a));
        int accent = Math.round(ch * UiAnim.easeOutCubic((age - 1250L) / 500f));
        g.fill(cx, y, cx + 2, y + accent, BloodFx.a(BloodFx.BRIGHT, a));
        float breathe = 0.5f + 0.5f * (float) Math.sin(now / 700.0);
        UiFx.brackets(g, cx, y, cw, ch, BloodFx.a(SILVER, 0.55f * a), 7, breathe, 2);

        String label = I18n.get(s.solo ? "maniacmod.loading.card.solo" : "maniacmod.loading.card.server");
        text(g, f, label, cx + 10, y + 7, 0.75f, BloodFx.a(BloodFx.HOT, 0.9f * a), false);

        String name;
        String sub;
        if (s.solo) {
            name = s.worldName != null ? s.worldName : "—";
            sub = I18n.get("maniacmod.loading.card.local");
        } else {
            name = s.serverName != null && !s.serverName.isBlank()
                ? s.serverName : I18n.get("maniacmod.loading.card.unknown");
            sub = s.serverAddr != null ? s.serverAddr : "";
        }
        int maxText = cw - 62;
        name = f.plainSubstrByWidth(name, (int) (maxText / 1.25f));
        sub = f.plainSubstrByWidth(sub, (int) (maxText / 0.85f));
        text(g, f, name, cx + 10, y + 18, 1.25f, BloodFx.a(SILVER, a), true);
        text(g, f, sub, cx + 10, y + 30, 0.85f, BloodFx.a(BODY, 0.85f * a), false);

        // «сигнал»: пока йде зʼєднання — хвиля, далі — усі смужки світяться
        int bx = cx + cw - 30;
        boolean connected = s.stageIdx >= (s.solo ? 1 : 2);
        int wave = (int) ((now / 220L) % 6L);
        for (int i = 0; i < 4; i++) {
            int bh = 4 + i * 3;
            boolean on = connected || wave > i;
            int col = on ? BloodFx.a(connected ? SILVER : BloodFx.BRIGHT, a)
                         : BloodFx.a(0xFF2A2F34, a);
            int bxi = bx + i * 5;
            g.fill(bxi, y + 28 - bh, bxi + 3, y + 28, col);
        }
    }

    // ═══════════════ Прогрес ═════════════════════════════════════════

    private static void drawProgressBlock(GuiGraphics g, Font f, ManiacLoadingSession s, int bx, int bw,
                                          int y0, boolean tipsOn, long age, long now, float beat, boolean exit) {
        float a = UiAnim.easeOutCubic((age - 700L) / 500f);
        if (a <= 0.01f) return;

        int count = s.stageCount();
        int active = exit ? count : s.stageIdx;

        // статус: друкується при зміні кроку; зліва блимає червона цятка
        String key = exit ? "maniacmod.loading.done" : "maniacmod.loading.status." + s.stageKey(s.stageIdx);
        String full = I18n.get(key).toUpperCase(Locale.ROOT);
        long since = exit ? 9999L : now - s.stageEnteredAt;
        int chars = Mth.clamp((int) (since / 22L), 0, full.length());
        String shownText = full.substring(0, chars);
        int dots = exit ? 0 : 1 + (int) ((now / 380L) % 3L);
        boolean typed = chars >= full.length();

        float dot = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
        g.fill(bx, y0 + 3, bx + 5, y0 + 8, BloodFx.a(exit ? SILVER : BloodFx.BRIGHT, a * (0.55f + 0.45f * dot)));
        text(g, f, shownText + (typed ? ".".repeat(dots) : "_"), bx + 11, y0 + 1, 1.0f, BloodFx.a(SILVER, a), true);

        int pct = Math.round(s.shown * 100f);
        if (!exit) pct = Math.min(99, pct);
        String pctStr = pct + "%";
        float ps = 2f;
        float pw = f.width(pctStr) * ps;
        text(g, f, pctStr, bx + bw - pw, y0 - 4, ps, BloodFx.a(SILVER, a), true);

        // смуга
        int barY = y0 + 22;
        int barH = 5;
        g.fill(bx, barY, bx + bw, barY + barH, BloodFx.a(0xFF05070A, 0.95f * a));
        BloodFx.border1(g, bx, barY, bw, barH, BloodFx.a(BORDER, a));
        int fw = Math.round((bw - 2) * Mth.clamp(s.shown, 0f, 1f));
        if (fw > 0) {
            g.fill(bx + 1, barY + 1, bx + 1 + fw, barY + barH - 1, BloodFx.a(BloodFx.MID, a));
            g.fill(bx + 1, barY + 1, bx + 1 + fw, barY + 2, BloodFx.a(BloodFx.HOT, 0.75f * a));
            UiFx.barGlint(g, bx + 1, barY + 1, fw, barH - 2, 1600L, 0f);
            UiFx.barHead(g, bx + 1, barY + 1, fw, barH - 2, 0xFF4A52, 0.6f + 0.4f * beat);
        }

        // вузли-кроки: ромби на смузі, підписи під нею
        for (int i = 0; i < count; i++) {
            int cx = bx + Math.round(bw * (i + 0.5f) / count);
            int cy = barY + barH / 2;
            boolean done = i < active;
            boolean cur = i == active;
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0f);
            g.pose().mulPose(Axis.ZP.rotationDegrees(45f));
            if (cur) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 320.0);
                g.fill(-6, -6, 6, 6, BloodFx.a(BloodFx.BRIGHT, (0.20f + 0.25f * pulse) * a));
                g.fill(-4, -4, 4, 4, BloodFx.a(BloodFx.HOT, a));
                g.fill(-2, -2, 2, 2, BloodFx.a(0xFFFFFFFF, a));
            } else if (done) {
                g.fill(-4, -4, 4, 4, BloodFx.a(0xFF0B0D0E, a));
                g.fill(-3, -3, 3, 3, BloodFx.a(SILVER, a));
            } else {
                g.fill(-4, -4, 4, 4, BloodFx.a(0xFF0B0D0E, a));
                BloodFx.border1(g, -4, -4, 8, 8, BloodFx.a(0xFF3A4046, a));
            }
            g.pose().popPose();

            String lbl = I18n.get("maniacmod.loading.stage." + s.stageKey(i)).toUpperCase(Locale.ROOT);
            float ls = 0.75f;
            float lw = f.width(lbl) * ls;
            int col = cur ? SILVER : (done ? BODY : DIM);
            text(g, f, lbl, cx - lw / 2f, barY + barH + 8, ls, BloodFx.a(col, a), cur);
        }

        if (tipsOn) drawTip(g, f, s, bx, barY + barH + 26, bw, age, a);
    }

    private static void drawTip(GuiGraphics g, Font f, ManiacLoadingSession s, int x, int y, int bw,
                                long age, float appear) {
        long slotMs = 6500L;
        long slot = age / slotMs;
        long local = age % slotMs;
        float env = Math.min(1f, local / 450f) * Math.min(1f, (slotMs - local) / 450f) * appear;
        if (env <= 0.02f) return;

        text(g, f, I18n.get("maniacmod.loading.tip"), x, y, 0.75f, BloodFx.a(BloodFx.HOT, 0.9f * env), false);
        int maxW = Math.min(bw, 440);
        List<FormattedCharSequence> lines = f.split(Component.literal(s.tips.get((int) slot)), maxW);
        int ly = y + 10;
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            g.drawString(f, lines.get(i), x, ly, BloodFx.a(BODY, env), false);
            ly += f.lineHeight + 1;
        }
    }

    // ═══════════════ Кнопка «Скасувати» ══════════════════════════════

    private static void drawCancel(GuiGraphics g, Font f, ManiacLoadingSession s, AbstractWidget btn,
                                   int mx, int my, long age) {
        float a = UiAnim.easeOutCubic((age - 900L) / 450f);
        if (a <= 0.01f) return;
        int x = btn.getX();
        int y = btn.getY();
        int bw = btn.getWidth();
        int bh = btn.getHeight();
        boolean hov = mx >= x && mx < x + bw && my >= y && my < y + bh;
        s.cancelHover = UiAnim.approach(s.cancelHover, hov ? 1f : 0f, 16f, 0.016f);

        g.fill(x, y, x + bw, y + bh, BloodFx.a(0xFF0A0B0C, 0.92f * a));
        int blade = Math.round(bw * UiAnim.easeOutCubic(s.cancelHover));
        if (blade > 0) {
            g.fill(x, y, x + blade, y + bh, BloodFx.a(BloodFx.MID, 0.9f * a));
            g.fill(x + Math.max(0, blade - 3), y, x + blade, y + bh, BloodFx.a(BloodFx.BRIGHT, 0.8f * a));
        }
        BloodFx.border1(g, x, y, bw, bh, BloodFx.a(BloodFx.mix(BORDER, BloodFx.HOT, s.cancelHover), a));
        String label = CommonComponents.GUI_CANCEL.getString().toUpperCase(Locale.ROOT);
        text(g, f, label, x + (bw - f.width(label) * 0.9f) / 2f, y + (bh - 8 * 0.9f) / 2f, 0.9f,
            BloodFx.a(BloodFx.mix(BODY, 0xFFFFFFFF, s.cancelHover), a), true);
    }

    // ═══════════════ Текст ═══════════════════════════════════════════

    /** Текст з масштабом. Альфа &lt; 4 у ванільному шрифті трактується як «непрозоро», тому відсікаємо. */
    private static void text(GuiGraphics g, Font f, String s, float x, float y, float scale, int argb, boolean shadow) {
        if ((argb >>> 24) < 4 || s.isEmpty()) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(f, s, 0, 0, argb, shadow);
        g.pose().popPose();
    }

    private static void glyph(GuiGraphics g, Font f, String ch, float x, float y, float scale, int argb) {
        if ((argb >>> 24) < 4) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(f, ch, 0, 0, argb, false);
        g.pose().popPose();
    }
}
