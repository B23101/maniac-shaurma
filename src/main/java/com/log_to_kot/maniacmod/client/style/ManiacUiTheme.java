package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Єдиний постачальник стилю для всіх панелей мода (міні-ігри,
 * generator-overlay, і будь-яка майбутня панель того самого сімейства
 * — "розбір завалу", "динаміт" тощо, коли дійде до їхньої механіки).
 *
 * ── Навіщо окремо від {@code dev.shaurmalib.forge.overlay.OverlayPanelStyle} ──
 * Бібліотечний стиль — простий прямокутник з 1px рамкою й опційним
 * кольоровим контуром, спільний для БУДЬ-ЯКОГО консюмера shaurma-lib.
 * Референс цього мода (скріни "Вирватись із пастки" / "Hold Shift+RMB"
 * / "Розбір завала") — інший, специфічний вигляд САМЕ цього мода:
 * — кутові акценти-дужки по рогах панелі (не суцільна рамка);
 * — заголовок КАПСОМ з горизонтальним роздільником під ним;
 * — окрема під-панель-підказка внизу (той самий стиль, менша);
 * — прямокутні (не заокруглені) прогрес-бари з чіткою внутрішньою
 *   рамкою, а не суцільна заливка без канту.
 * Форкати чи розширювати бібліотечний клас під це не варто — це
 * вузькоспецифічний вигляд одного мода, а не щось, що інший консюмер
 * lib захоче перевикористати.
 *
 * Усі методи статичні й безстанові — самі екрани/оверлеї тримають
 * власний стан (позиція повзунка, прогрес), цей клас лише малює.
 */
public final class ManiacUiTheme {

    private ManiacUiTheme() {}

    // ── Кольори ─────────────────────────────────────────────────────────
    // Палітра меню (задана напряму, ARGB): фон меню 0a0c0b, заливка
    // слотів/ударів/пасток/сил маньяка 0b0b0b, решта акцентів меню
    // cfb471 / 0f1315 / 292d2e / 433e30.
    public static final int MENU_BG = 0xFF0A0C0B;
    public static final int SLOT_FILL = 0xFF0B0B0B;
    public static final int MENU_ACCENT_GOLD = 0xFFCFB471;
    public static final int MENU_PANEL_DARK = 0xFF0F1315;
    public static final int MENU_BORDER_MUTED = 0xFF292D2E;
    public static final int MENU_ACCENT_BROWN = 0xFF433E30;

    public static final int PANEL_BG = 0xE60A0C0B;
    public static final int PANEL_BG_SOLID = MENU_BG;
    public static final int BORDER = MENU_BORDER_MUTED;
    public static final int BORDER_BRIGHT = 0xFFB9B9B9;
    public static final int CORNER_ACCENT = MENU_ACCENT_GOLD; // золотистий кутовий акцент (як image 4 — лівий верхній кут)
    public static final int DIVIDER = MENU_BORDER_MUTED;
    public static final int TEXT_TITLE = 0xFFF2F2F2;
    public static final int TEXT_BODY = 0xFFB9B9B9;
    public static final int TEXT_DIM = 0xFF7A7A7A;
    public static final int TEXT_ACCENT = MENU_ACCENT_GOLD;

    public static final int BAR_FRAME = 0xFFB9B9B9;
    public static final int BAR_BG = MENU_PANEL_DARK;
    public static final int BAR_FILL_NEUTRAL = 0xFFC7CCD1;
    public static final int BAR_FILL_WARN = 0xFFE0A030;
    public static final int BAR_FILL_DANGER = 0xFFB0242F;

    private static final int CORNER_SIZE = 10;
    private static final int CORNER_THICKNESS = 2;

    // ── Панель ──────────────────────────────────────────────────────────

    /**
     * Основна панель: темний фон + тонка рамка + 4 кутові акценти-дужки
     * по рогах (той самий "L"-штрих у кожному з 4 кутів, довший акцент
     * лише у верхньому лівому — як на референсі "Розбір завала", де
     * лише один кут підсвічений золотим, решта — звичайний сірий).
     */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        drawPanel(g, x, y, w, h, false);
    }

    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, boolean accentTopLeft) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        border1px(g, x, y, w, h, BORDER);
        drawCorner(g, x, y, 1, 1, accentTopLeft ? CORNER_ACCENT : BORDER_BRIGHT);
        drawCorner(g, x + w, y, -1, 1, BORDER_BRIGHT);
        drawCorner(g, x, y + h, 1, -1, BORDER_BRIGHT);
        drawCorner(g, x + w, y + h, -1, -1, BORDER_BRIGHT);
    }

    /** Один кутовий акцент — L-подібний штрих, що росте від кута (x,y) у напрямку (dx,dy) = ±1. */
    private static void drawCorner(GuiGraphics g, int x, int y, int dx, int dy, int color) {
        int t = CORNER_THICKNESS;
        int s = CORNER_SIZE;
        // Горизонтальний штрих кута.
        int hx0 = dx > 0 ? x : x - s;
        int hx1 = dx > 0 ? x + s : x;
        int hy0 = dy > 0 ? y : y - t;
        int hy1 = dy > 0 ? y + t : y;
        g.fill(hx0, hy0, hx1, hy1, color);
        // Вертикальний штрих кута.
        int vx0 = dx > 0 ? x : x - t;
        int vx1 = dx > 0 ? x + t : x;
        int vy0 = dy > 0 ? y : y - s;
        int vy1 = dy > 0 ? y + s : y;
        g.fill(vx0, vy0, vx1, vy1, color);
    }

    public static void border1px(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Горизонтальний роздільник (той самий, що під заголовком на всіх референсах). */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, DIVIDER);
    }

    // ── Заголовок ───────────────────────────────────────────────────────

    /**
     * Заголовок капсом по центру ширини панелі + роздільник під ним.
     * Повертає Y-координату ОДРАЗУ під роздільником — звідти консюмер
     * продовжує викладати вміст, не передивляючись висоту заголовка
     * вручну щоразу.
     */
    public static int drawTitle(GuiGraphics g, Font font, Component title, int panelX, int panelY, int panelW) {
        int centerX = panelX + panelW / 2;
        int titleY = panelY + 14;
        g.drawCenteredString(font, title.getString().toUpperCase(java.util.Locale.ROOT), centerX, titleY, TEXT_TITLE);
        int dividerY = titleY + font.lineHeight + 8;
        divider(g, panelX + 16, dividerY, panelW - 32);
        return dividerY + 10;
    }

    /** Той самий заголовок, але БЕЗ капіталізації (для динамічних рядків типу лічильників, де капс недоречний). */
    public static int drawTitleRaw(GuiGraphics g, Font font, String title, int panelX, int panelY, int panelW) {
        int centerX = panelX + panelW / 2;
        int titleY = panelY + 14;
        g.drawCenteredString(font, title, centerX, titleY, TEXT_TITLE);
        int dividerY = titleY + font.lineHeight + 8;
        divider(g, panelX + 16, dividerY, panelW - 32);
        return dividerY + 10;
    }

    /** Багаторядковий підзаголовок-опис (як "Ищите глубокую трещину..." на референсі) — центрований, тьмяний. */
    public static int drawSubtitleLines(GuiGraphics g, Font font, List<String> lines, int panelX, int y, int panelW) {
        int centerX = panelX + panelW / 2;
        int cursorY = y;
        for (String line : lines) {
            g.drawCenteredString(font, line, centerX, cursorY, TEXT_BODY);
            cursorY += font.lineHeight + 2;
        }
        return cursorY;
    }

    // ── Підказка-панель знизу (окрема менша панель того самого стилю) ─────

    /**
     * Панель-підказка під основною (як "Пробел — остановить" / "Hold
     * Shift+RMB to repair the generator"): та сама рамка й фон, без
     * кутових акцентів (вони лише на головній панелі), одна строка
     * тексту по центру.
     */
    public static void drawHintBar(GuiGraphics g, Font font, String text, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        border1px(g, x, y, w, h, BORDER);
        g.drawCenteredString(font, text, x + w / 2, y + (h - font.lineHeight) / 2, TEXT_BODY);
    }

    // ── Прогрес-бар: пряма рамка (без заокруглень) + внутрішня заливка ────

    /**
     * Прямокутний прогрес-бар точно як на референсах "Вирватись з
     * пастки"/"Hold Shift+RMB": зовнішня світло-сіра рамка товщиною
     * {@link #BAR_FRAME_THICKNESS}, всередині темний фон, зверху —
     * заливка кольором fillColor на ширину fraction від внутрішньої
     * зони. Fraction 0..1, значення поза межами затискається.
     */
    private static final int BAR_FRAME_THICKNESS = 2;

    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, float fraction, int fillColor) {
        g.fill(x, y, x + w, y + h, BAR_FRAME);
        int ix = x + BAR_FRAME_THICKNESS, iy = y + BAR_FRAME_THICKNESS;
        int iw = w - BAR_FRAME_THICKNESS * 2, ih = h - BAR_FRAME_THICKNESS * 2;
        g.fill(ix, iy, ix + iw, iy + ih, BAR_BG);

        float f = Math.max(0f, Math.min(1f, fraction));
        int filled = Math.round(iw * f);
        if (filled > 0) {
            g.fill(ix, iy, ix + filled, iy + ih, fillColor);
        }
    }

    /** Той самий бар, але з вертикальною позначкою "цілі" на певній частці ширини (target-мінігра). */
    public static void drawTickMark(GuiGraphics g, int barX, int barY, int barW, int barH, float fraction, int color, int overshoot) {
        int tickX = barX + Math.round(barW * Math.max(0f, Math.min(1f, fraction)));
        g.fill(tickX - 1, barY - overshoot, tickX + 1, barY + barH + overshoot, color);
    }
}
