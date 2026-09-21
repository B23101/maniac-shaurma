package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Стиль САМЕ для панелі прогресу генератора (картинка-референс "Бензин
 * 23%" / "Нажмите Shift+ПКМ, чтобы чинить генератор") — навмисно
 * ОКРЕМИЙ клас від {@link ManiacUiTheme}.
 *
 * ── Чому не просто нові кольори в ManiacUiTheme ────────────────────────
 * ManiacUiTheme — це фірмовий стиль меню й міні-ігор мода (золотистий
 * акцент, кутові дужки-акценти, капс-заголовки). Раніше ця панель мала
 * власний, окремий вигляд без жодного золота (фаска-бевел замість
 * рамки-меню). Це виглядало як два різні UI в одному моді — панель
 * генератора (лагодження/залив) і підказка над нею мали ІНШИЙ фон, ніж
 * решта HUD мода (меню, RescueOverlay). {@link #drawPanel} тепер
 * малює ТОЙ САМИЙ фон і рамку, що {@link ManiacUiTheme#drawPanel} (без
 * кутових акцентів — вони лишаються унікальною рисою головного меню/
 * міні-ігор, тут зайві), а не власну фаску. Прогрес-бар лишається
 * своїм: заглиблений трек — деталь САМЕ бару, не фону панелі, і той
 * вигляд, який просили зберегти.
 *
 * Усі кольори — ARGB (0xAARRGGBB).
 */
public final class GeneratorFuelUiTheme {

    private GeneratorFuelUiTheme() {}

    // ── Кольори ─────────────────────────────────────────────────────────

    /**
     * Фон панелі. БАГФІКС/уніфікація вигляду: раніше власний
     * {@code 0xFF0B0E14}, тепер той самий напівпрозорий темний фон, що в
     * {@link ManiacUiTheme#PANEL_BG} — генератор і решта HUD мода більше
     * не виглядають як два різні інтерфейси.
     */
    public static final int PANEL_BG = ManiacUiTheme.PANEL_BG;

    /** Рамка панелі — той самий приглушений сірий, що в меню. */
    public static final int PANEL_BORDER = ManiacUiTheme.BORDER;

    /** Внутрішній фон бару/заглиблених елементів — майже чистий чорний. */
    public static final int INSET_BG = 0xFF05070A;

    /** Заповнення бару й активні елементи — світло-попелястий сріблястий. */
    public static final int FILL = 0xFFC2C9D6;

    /** Зона влучання QTE — середньо-сірий з холодним підтоном. */
    public static final int HIT_ZONE = 0xFF7E8896;

    /** Основний текст. */
    public static final int TEXT = 0xFFFFFFFF;

    /** Роздільник між заголовком і рештою панелі. */
    public static final int DIVIDER = 0xFF1F242D;

    /** Фаска: світліший край (верх/ліво) — імітує світло зверху. Лишається для бару (заглиблення). */
    public static final int BEVEL_LIGHT = 0xFF303742;

    /** Фаска: темніший край (низ/право) — імітує тінь. Лишається для бару (заглиблення). */
    public static final int BEVEL_DARK = 0xFF12161E;

    /** Відступ роздільника від країв панелі, px. */
    public static final int DIVIDER_INSET = 9;

    // ── Панель: той самий фон/рамка, що в меню ─────────────────────────────

    /**
     * Прямокутна панель — фон і 1px рамка в стилі
     * {@link ManiacUiTheme}, БЕЗ кутових акцентів-дужок (ті лишаються
     * унікальною рисою головного меню/міні-ігор) і без бевел-ефекту, що
     * був тут раніше.
     */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        ManiacUiTheme.border1px(g, x, y, w, h, PANEL_BORDER);
    }

    /**
     * Той самий бевел, але для ВДАВЛЕНОГО елемента (бар, заглиблення):
     * тут верх/ліво — темні, низ/право — світлі, тобто напрямок фаски
     * протилежний {@link #bevelBorder} — так внутрішній бар читається
     * як заглиблений у панель.
     */
    public static void bevelBorderInset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BEVEL_DARK);
        g.fill(x, y, x + 1, y + h, BEVEL_DARK);
        g.fill(x, y + h - 1, x + w, y + h, BEVEL_LIGHT);
        g.fill(x + w - 1, y, x + w, y + h, BEVEL_LIGHT);
    }

    /** 1px фаска-рамка, що ВИСТУПАЄ: світла зверху/зліва, темна знизу/справа. Лишена для сумісності викликів. */
    public static void bevelBorder(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BEVEL_LIGHT);
        g.fill(x, y, x + 1, y + h, BEVEL_LIGHT);
        g.fill(x, y + h - 1, x + w, y + h, BEVEL_DARK);
        g.fill(x + w - 1, y, x + w, y + h, BEVEL_DARK);
    }

    /** Горизонтальний роздільник на всю ширину мінус {@link #DIVIDER_INSET} з кожного боку. */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x + DIVIDER_INSET, y, x + w - DIVIDER_INSET, y + 1, DIVIDER);
    }

    // ── Прогрес-бар: заглиблений трек + рівна заливка (без рамки навколо заливки) ──

    /**
     * Прогрес-бар у стилі референсу: заглиблений (inset-фаска) трек на
     * {@link #INSET_BG}, заливка суцільним {@link #FILL} без власної
     * рамки навколо себе — на відміну від {@code ManiacUiTheme}, де
     * заливка теж у товстій рамці, тут рамка лише в самого треку.
     */
    public static void drawBar(GuiGraphics g, int x, int y, int w, int h, float fraction) {
        g.fill(x, y, x + w, y + h, INSET_BG);
        bevelBorderInset(g, x, y, w, h);

        float f = Math.max(0f, Math.min(1f, fraction));
        int filled = Math.round((w - 2) * f);
        if (filled > 0) {
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, FILL);
        }
    }

    /** Заголовок-рядок: назва зліва, значення справа, той самий рядок (як "Бензин" / "23%"). */
    public static void drawTitleRow(GuiGraphics g, Font font, String left, String right,
                                     int x, int y, int w) {
        g.drawString(font, left, x, y, TEXT, false);
        int rightWidth = font.width(right);
        g.drawString(font, right, x + w - rightWidth, y, TEXT, false);
    }

    /** Той самий заголовок-рядок, але лише лівий текст (без значення справа) — для панелі-підказки. */
    public static void drawTitleLeft(GuiGraphics g, Font font, String text, int x, int y) {
        g.drawString(font, text, x, y, TEXT, false);
    }
}
