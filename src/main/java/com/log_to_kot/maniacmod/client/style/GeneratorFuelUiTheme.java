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
        topSheen(g, x, y, w, 0f);
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
     * {@link #INSET_BG}, заливка суцільним {@link #FILL}. Тепер із
     * м'яким «бліком», що біжить по заливці ({@link #drawBarFx}), — той
     * самий вигляд, але живий. Усі виклики зі старого API отримують блик
     * автоматично.
     */
    public static void drawBar(GuiGraphics g, int x, int y, int w, int h, float fraction) {
        drawBarFx(g, x, y, w, h, fraction, fraction, FILL, 0f);
    }

    // ── Кольори станів (додаткові до срібла) ─────────────────────────────

    /** Небезпека (мало хп, час до смерті) — той самий, що {@code ManiacUiTheme.BAR_FILL_DANGER}. */
    public static final int FILL_DANGER = 0xFFB0242F;
    /** Попередження (мало стаміни, поспішай) — {@code ManiacUiTheme.BAR_FILL_WARN}. */
    public static final int FILL_WARN = 0xFFE0A030;
    /** «Привид» втраченої частини бару — тьмяно-сірий слід, що повільно з'їдається. */
    public static final int GHOST = 0xFF56606E;
    /** Світло виділення (вибраний слот, готовність). */
    public static final int HIGHLIGHT = 0xFFF2F4F8;

    // ── Анімовані примітиви ──────────────────────────────────────────────

    /** Змінює альфу ARGB-кольору множником {@code a} (0..1) — для плавної появи/згасання. */
    public static int alpha(int argb, float a) {
        int base = (argb >>> 24) & 0xFF;
        int na = Math.round(base * Math.max(0f, Math.min(1f, a)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    /** Лінійне змішування двох ARGB-кольорів (t=0 → a, t=1 → b). */
    public static int mix(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int aa = Math.round(((a >>> 24) & 0xFF) * (1 - k) + ((b >>> 24) & 0xFF) * k);
        int ar = Math.round(((a >>> 16) & 0xFF) * (1 - k) + ((b >>> 16) & 0xFF) * k);
        int ag = Math.round(((a >>> 8) & 0xFF) * (1 - k) + ((b >>> 8) & 0xFF) * k);
        int ab = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    /**
     * Прогрес-бар із ефектами: слід втраченої частини, спалах, блик.
     *
     * @param fraction поточна заповненість 0..1
     * @param ghost    «привид» — де бар БУВ (зазвичай ≥ fraction; різниця
     *                 малюється тьмяним слідом, що з'їдає оверлей)
     * @param color    колір заливки (звичайно {@link #FILL})
     * @param flash    0..1 — білий спалах заливки (удар/лікування)
     */
    public static void drawBarFx(GuiGraphics g, int x, int y, int w, int h,
                                 float fraction, float ghost, int color, float flash) {
        g.fill(x, y, x + w, y + h, INSET_BG);
        bevelBorderInset(g, x, y, w, h);

        int inner = w - 2;
        int filled = Math.round(inner * Math.max(0f, Math.min(1f, fraction)));
        int ghostW = Math.round(inner * Math.max(0f, Math.min(1f, ghost)));
        int x0 = x + 1, y0 = y + 1, y1 = y + h - 1;

        if (ghostW > filled) g.fill(x0 + filled, y0, x0 + ghostW, y1, GHOST);
        if (filled <= 0) return;

        g.fill(x0, y0, x0 + filled, y1, color);
        // Верхній відблиск усередині заливки.
        g.fill(x0, y0, x0 + filled, y0 + 1, 0x38FFFFFF);

        // Блик, що біжить зліва направо (період ~1.8 с, з паузою).
        long now = System.currentTimeMillis();
        float sweep = ((now % 1800L) / 1800f) * 1.6f - 0.3f;
        int band = Math.max(6, inner / 9);
        int center = Math.round(sweep * filled);
        for (int i = -band; i <= band; i++) {
            int cx = center + i;
            if (cx < 0 || cx >= filled) continue;
            float k = 1f - Math.abs(i) / (float) band;
            int a = Math.round(0x46 * k * k);
            g.fill(x0 + cx, y0, x0 + cx + 1, y1, (a << 24) | 0xFFFFFF);
        }

        // Яскравий край заливки — «голівка» бару.
        g.fill(x0 + filled - 1, y0, x0 + filled, y1, 0x90FFFFFF);

        if (flash > 0.01f) {
            g.fill(x0, y0, x0 + filled, y1, alpha(0xFFFFFFFF, flash * 0.75f));
        }
    }

    /** Вертикальний варіант {@link #drawBarFx}: заливка росте ЗНИЗУ ВГОРУ. */
    public static void drawBarVFx(GuiGraphics g, int x, int y, int w, int h,
                                  float fraction, float ghost, int color, float flash) {
        g.fill(x, y, x + w, y + h, INSET_BG);
        bevelBorderInset(g, x, y, w, h);

        int inner = h - 2;
        int filled = Math.round(inner * Math.max(0f, Math.min(1f, fraction)));
        int ghostH = Math.round(inner * Math.max(0f, Math.min(1f, ghost)));
        int x0 = x + 1, x1 = x + w - 1, yb = y + h - 1;

        if (ghostH > filled) g.fill(x0, yb - ghostH, x1, yb - filled, GHOST);
        if (filled <= 0) return;

        g.fill(x0, yb - filled, x1, yb, color);
        g.fill(x0, yb - filled, x0 + 1, yb, 0x38FFFFFF);

        long now = System.currentTimeMillis();
        float sweep = 1.3f - ((now % 1500L) / 1500f) * 1.6f;
        int band = Math.max(5, inner / 9);
        int center = Math.round(sweep * filled);
        for (int i = -band; i <= band; i++) {
            int cy = center + i;
            if (cy < 0 || cy >= filled) continue;
            float k = 1f - Math.abs(i) / (float) band;
            int a = Math.round(0x46 * k * k);
            g.fill(x0, yb - cy - 1, x1, yb - cy, (a << 24) | 0xFFFFFF);
        }
        g.fill(x0, yb - filled, x1, yb - filled + 1, 0x90FFFFFF);

        if (flash > 0.01f) {
            g.fill(x0, yb - filled, x1, yb, alpha(0xFFFFFFFF, flash * 0.75f));
        }
    }

    /**
     * Слот (предмет / іконка / кнопка): той самий фон і рамка, що панель,
     * плюс внутрішня фаска, щоб слот читався як заглиблення в HUD.
     */
    public static void drawSlot(GuiGraphics g, int x, int y, int w, int h, int borderColor) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x66000000);
        ManiacUiTheme.border1px(g, x, y, w, h, borderColor);
    }

    /** Ліхтар по верхній грані панелі — світла смужка, що повільно ковзає (живий HUD). */
    public static void topSheen(GuiGraphics g, int x, int y, int w, float phaseOffset) {
        long now = System.currentTimeMillis();
        float t = (((now % 3200L) / 3200f) + phaseOffset) % 1f;
        int band = Math.max(12, w / 5);
        int cx = Math.round(t * (w + band * 2)) - band;
        for (int i = -band; i <= band; i++) {
            int px = cx + i;
            if (px < 1 || px >= w - 1) continue;
            float k = 1f - Math.abs(i) / (float) band;
            int a = Math.round(0x50 * k);
            g.fill(x + px, y, x + px + 1, y + 1, (a << 24) | 0xFFFFFF);
        }
    }

    /** Розширювальне кільце-«пульс» навколо прямокутника: t 0..1 (розшир/згасання). */
    public static void pulseRing(GuiGraphics g, int x, int y, int w, int h, int color, float t, int maxGrow) {
        if (t <= 0f || t >= 1f) return;
        int grow = Math.round(t * maxGrow);
        int c = alpha(color, (1f - t) * (1f - t));
        ManiacUiTheme.border1px(g, x - grow, y - grow, w + grow * 2, h + grow * 2, c);
    }

    /** Кадро-незалежний вхід: підйом знизу + м'який масштаб навколо центру. Закривати {@link #endAppear}. */
    public static void beginAppear(GuiGraphics g, float cx, float cy, float ease, float rise) {
        g.pose().pushPose();
        g.pose().translate(cx, cy + (1f - ease) * rise, 0f);
        float sc = 0.94f + 0.06f * ease;
        g.pose().scale(sc, sc, 1f);
        g.pose().translate(-cx, -cy, 0f);
    }

    public static void endAppear(GuiGraphics g) {
        g.pose().popPose();
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
