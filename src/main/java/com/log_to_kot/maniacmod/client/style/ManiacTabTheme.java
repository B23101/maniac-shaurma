package com.log_to_kot.maniacmod.client.style;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.shaurmalib.forge.tab.DefaultTabTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Тема tab-екрана САМЕ цього мода: холодне срібло + кривавий акцент,
 * скоби, що дихають, скан-лінії й періодичний промінь по панелі.
 *
 * ── Чому окрема тема, а не правки в shaurma-lib ─────────────────────────
 * Бібліотечний {@link dev.shaurmalib.forge.tab.TabListStyle} тепер
 * делегує весь вигляд у {@link dev.shaurmalib.forge.tab.TabTheme}
 * (див. її докстрінг). Тут — реалізація «Маньяка»: успадковує
 * {@link DefaultTabTheme} і перевизначає лише те, що має виглядати
 * інакше, ніж золото snipers_shaurma. Усе, чого тема не чіпає (командна
 * панель, заголовки колонок), лишається дефолтним і працює.
 *
 * Палітра узгоджена з {@link ManiacUiTheme}/{@link GeneratorFuelUiTheme}:
 * ті самі фон, рамка й срібло, що в меню та панелях генератора —
 * tab не виглядає «чужим» серед решти HUD. Кривавий {@link #BLOOD} —
 * єдиний новий колір, і він належить лише маньяку/небезпеці.
 *
 * Метрики ({@link #rowHeight()} 24 замість 16) більші за дефолтні: рядок
 * тут несе голову, ім'я, під-рядок стану, шкалу хп і ЕКГ.
 *
 * Тема тримає лише безстанові обчислення від {@code tick} — жодних
 * власних анімаційних мап, тому один instance безпечно жити довше кадру.
 */
public final class ManiacTabTheme extends DefaultTabTheme {

    // ── Палітра ────────────────────────────────────────────────────────
    public static final int BLOOD      = 0xFFC8232F;
    public static final int BLOOD_HOT  = 0xFFFF4B55;
    public static final int BLOOD_DEEP = 0xFF3A0A10;
    public static final int SILVER     = ManiacUiTheme.MENU_ACCENT_GOLD;
    public static final int SILVER_HI  = ManiacUiTheme.TEXT_ACCENT;
    public static final int BG         = ManiacUiTheme.MENU_BG;
    public static final int CARD       = 0xFF0D1012;
    public static final int CARD_EDGE  = ManiacUiTheme.MENU_BORDER_MUTED;
    public static final int DIM        = 0xFF7F8896;
    public static final int OK         = 0xFF43C463;
    public static final int WARN       = 0xFFE0A030;
    public static final int CYAN       = 0xFF4FD8FF;

    @Override public int headSize()           { return 16; }
    @Override public int rowHeight()          { return 24; }
    @Override public int titleHeight()        { return 30; }
    @Override public int columnHeaderHeight() { return 14; }
    @Override public int headOffsetX()        { return 8; }
    @Override public int nameOffsetX()        { return 30; }
    @Override public int rankOffsetX()        { return 2; }

    // ── Панель ─────────────────────────────────────────────────────────

    @Override
    public void drawPanel(GuiGraphics g, int x, int y, int w, int h, float anim, long tick) {
        float breathe = 0.5f + 0.5f * (float) Math.sin(tick * 0.045);

        g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(0xF2080A0A, anim));
        // Кривавий «підсвіт знизу» — панель ніби стоїть над жаром.
        g.fillGradient(x, y + h / 2, x + w, y + h,
            GeneratorFuelUiTheme.alpha(0x00000000, anim),
            GeneratorFuelUiTheme.alpha(0x40C8232F, anim * (0.6f + 0.4f * breathe)));

        // Скан-лінії CRT: кожен 3-й піксель ледь темніший.
        int lineA = Math.round(0x10 * anim);
        if (lineA > 0) {
            for (int yy = y + 2; yy < y + h - 2; yy += 3) {
                g.fill(x + 1, yy, x + w - 1, yy + 1, lineA << 24);
            }
        }

        ManiacUiTheme.border1px(g, x, y, w, h, GeneratorFuelUiTheme.alpha(CARD_EDGE, anim));
        // Верхня грань: срібна лінія з кривавою вставкою по центру.
        g.fill(x + 1, y, x + w - 1, y + 1, GeneratorFuelUiTheme.alpha(SILVER, anim * 0.85f));
        int bw = Math.min(w / 3, 96);
        g.fill(x + (w - bw) / 2, y, x + (w + bw) / 2, y + 2,
            GeneratorFuelUiTheme.alpha(BLOOD_HOT, anim * (0.75f + 0.25f * breathe)));

        UiFx.glow(g, x, y, w, h, 0xB0242F, anim * (0.10f + 0.10f * breathe), 4);
        UiFx.brackets(g, x, y, w, h, GeneratorFuelUiTheme.alpha(SILVER_HI, anim * 0.9f), 12, breathe, 3);

        // Рідкісний промінь, що пролітає всю панель (раз на ~7 c).
        float beam = ((tick % 420L) / 420f) * 1.35f;
        if (beam < 1f) {
            UiFx.scanBeam(g, x + 1, y + 1, w - 2, h - 2, beam, 22, 0xFFFFFF, Math.round(16 * anim));
        }
    }

    // ── Заголовок: по центру, з періодичним «глітчем» ───────────────────

    @Override
    public void drawHeader(GuiGraphics g, Font font, int cx, int sy, float anim,
                           String titleKey, String subtitleKey, long tick) {
        String title = Component.translatable(titleKey).getString().toUpperCase(Locale.ROOT);
        float scale = 1.5f;
        int tw = Math.round(font.width(title) * scale);
        int tx = cx - tw / 2;
        int ty = sy + 6;

        // Глітч: кожні ~5 с на 9 кадрів заголовок розщеплюється на канали.
        boolean glitch = (tick % 300L) < 9L;
        if (glitch) {
            int j = (int) ((tick * 7L) % 3L) + 1;
            text(g, font, title, tx - j, ty, scale, GeneratorFuelUiTheme.alpha(BLOOD_HOT, anim * 0.8f));
            text(g, font, title, tx + j, ty + 1, scale, GeneratorFuelUiTheme.alpha(CYAN, anim * 0.6f));
        }
        text(g, font, title, tx, ty, scale, GeneratorFuelUiTheme.alpha(0xFFF2F4F8, anim));

        // Кривавий «пульс» з обох боків заголовка.
        float p = 0.5f + 0.5f * (float) Math.sin(tick * 0.09);
        int lineY = ty + Math.round(font.lineHeight * scale / 2f);
        int len = 46;
        g.fill(tx - 10 - len, lineY, tx - 10, lineY + 1, GeneratorFuelUiTheme.alpha(BLOOD, anim * (0.5f + 0.4f * p)));
        g.fill(tx + tw + 10, lineY, tx + tw + 10 + len, lineY + 1, GeneratorFuelUiTheme.alpha(BLOOD, anim * (0.5f + 0.4f * p)));
        g.fill(tx - 13, lineY - 2, tx - 10, lineY + 3, GeneratorFuelUiTheme.alpha(BLOOD_HOT, anim));
        g.fill(tx + tw + 10, lineY - 2, tx + tw + 13, lineY + 3, GeneratorFuelUiTheme.alpha(BLOOD_HOT, anim));

        if (subtitleKey != null) {
            String sub = Component.translatable(subtitleKey).getString().toUpperCase(Locale.ROOT);
            float ss = 0.75f;
            int sw = Math.round(font.width(sub) * ss);
            text(g, font, sub, cx - sw / 2, sy + 21, ss, GeneratorFuelUiTheme.alpha(DIM, anim));
        }
    }

    // ── Рядок ──────────────────────────────────────────────────────────

    @Override
    public void drawRowBg(GuiGraphics g, int x, int y, int w, int h, int rank,
                          boolean isMe, int teamTint, float anim, long tick) {
        int stripe = (rank % 2 == 0) ? 0xFF0E1214 : 0xFF12171A;
        g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(stripe, anim * 0.92f));
        if (teamTint != 0) {
            g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(0xFF000000 | (teamTint & 0xFFFFFF), anim * 0.10f));
        }
        g.fill(x, y + h - 1, x + w, y + h, GeneratorFuelUiTheme.alpha(0xFF1C2226, anim));
        if (isMe) {
            float p = 0.5f + 0.5f * (float) Math.sin(tick * 0.07);
            g.fill(x, y, x + w, y + h - 1, GeneratorFuelUiTheme.alpha(SILVER, anim * (0.07f + 0.05f * p)));
            g.fill(x, y, x + 2, y + h - 1, GeneratorFuelUiTheme.alpha(SILVER_HI, anim));
        }
    }

    @Override
    public void drawGoalPulse(GuiGraphics g, int x, int y, int w, int h, float anim, long tick) {
        float p = 0.5f + 0.5f * (float) Math.sin(tick * 0.08);
        g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(OK, anim * (0.10f + 0.16f * p)));
        g.fill(x, y, x + 2, y + h, GeneratorFuelUiTheme.alpha(OK, anim));
    }

    /** «Відродження» тут — це лежачий без свідомості: пульсує кривавим, а не помаранчевим. */
    @Override
    public void drawRespawning(GuiGraphics g, Font font, int x0, int rowW, int rowH,
                               int textX, int textY, String name, String respawnLabel,
                               float anim, long tick) {
        float p = 0.5f + 0.5f * (float) Math.sin(tick * 0.16);
        int rowY = textY - (rowH - font.lineHeight) / 2;
        g.fill(x0, rowY, x0 + rowW, rowY + rowH - 1, GeneratorFuelUiTheme.alpha(BLOOD, anim * (0.08f + 0.18f * p)));
        g.fill(x0, rowY, x0 + 2, rowY + rowH - 1, GeneratorFuelUiTheme.alpha(BLOOD_HOT, anim * (0.5f + 0.5f * p)));
        g.drawString(font, name, textX, textY, GeneratorFuelUiTheme.alpha(0xFFFFB0B5, anim), false);
    }

    // ── Голова: рамка + фолбек, поважає глобальний множник кольору ──────

    @Override
    public void drawHead(GuiGraphics g, ResourceLocation skin, int x, int y, int size,
                         float anim, long tick) {
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, GeneratorFuelUiTheme.alpha(0xFF1E2529, anim));
        if (skin == null) {
            g.fill(x, y, x + size, y + size, GeneratorFuelUiTheme.alpha(0xFF0A0C0D, anim));
            return;
        }
        // Глобальну прозорість панелі (shader color) НЕ затираємо — лише
        // множимо на неї, як UiFx.blitTinted.
        float[] prev = RenderSystem.getShaderColor().clone();
        try {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(prev[0], prev[1], prev[2], prev[3] * Math.max(0f, Math.min(1f, anim)));
            g.blit(skin, x, y, size, size, 8, 8, 8, 8, 64, 64);
            g.blit(skin, x, y, size, size, 40, 8, 8, 8, 64, 64);
        } catch (RuntimeException ignored) {
            // голова — декор: збій скіна не має валити весь tab
        } finally {
            RenderSystem.setShaderColor(prev[0], prev[1], prev[2], prev[3]);
        }
    }

    // ── Допоміжне ──────────────────────────────────────────────────────

    private static void text(GuiGraphics g, Font font, String s, int x, int y, float scale, int argb) {
        if ((argb >>> 24) < 6) return; // drawString робить альфу < 4 НЕПРОЗОРОЮ
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, argb, false);
        pose.popPose();
    }
}
