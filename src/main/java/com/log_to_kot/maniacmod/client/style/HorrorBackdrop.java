package com.log_to_kot.maniacmod.client.style;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Спільний фон повноекранних меню мода (ESC, оцінки): затемнення,
 * повільне «дихання» червоного віньєтування і {@link BloodFx.Curtain} —
 * кров, що стікає з верху екрана.
 *
 * ── Чому фон спокійний ───────────────────────────────────────────────
 * Перша версія мала зерно плівки, що перегенерувалось кожні 70 мс,
 * випадкові глітч-смуги, світлий промінь по всьому екрану й червоний
 * спалах при відкритті — усе це читалось як мерехтіння. Тепер у фоні
 * рухається лише те, що рухається ПЛАВНО: віньєтка дихає повільно й
 * неглибоко, кров росте й повзе. Жодних різких перепадів яскравості
 * між кадрами; відкриття — м'яке наростання, а не спалах.
 *
 * ── Один екземпляр = одне відкриття екрана ───────────────────────────
 * Статичного стану немає. Параметр {@code life}: 1 — відкрито,
 * 0 — закрилось (анімація закриття).
 */
public final class HorrorBackdrop {

    /** Тривалість м'якого наростання фону при відкритті, мс. */
    private static final float FADE_IN_MS = 380f;

    private final long seed;
    private final long openedAt;
    private final BloodFx.Curtain curtain;

    public HorrorBackdrop() {
        this.openedAt = Util.getMillis();
        this.seed = openedAt ^ 0x9E3779B97F4A7C15L;
        this.curtain = new BloodFx.Curtain(seed, openedAt);
    }

    public long openedAt() { return openedAt; }

    public long age(long now) { return now - openedAt; }

    /**
     * Повільне серцебиття «туп-ту-туп» (~0.8 Гц) з ШИРОКИМИ піками —
     * гладка функція без різких фронтів. Повертає 0..1.
     */
    public float beat(long now) {
        float phase = ((now - openedAt) / 1000f * 0.8f) % 1f;
        float b1 = (float) Math.exp(-Math.pow((phase - 0.08f) / 0.10f, 2));
        float b2 = 0.55f * (float) Math.exp(-Math.pow((phase - 0.34f) / 0.11f, 2));
        return Math.min(1f, b1 + b2);
    }

    public void render(GuiGraphics g, int w, int h, float life) {
        long now = Util.getMillis();
        float fadeIn = UiAnim.easeOutCubic((now - openedAt) / FADE_IN_MS);
        float lf = Mth.clamp(life, 0f, 1f) * fadeIn;
        float beat = beat(now);

        // 1) затемнення
        g.fillGradient(0, 0, w, h, BloodFx.a(0xFF0B0809, 0.88f * lf), BloodFx.a(0xFF030304, 0.95f * lf));

        // 2) тепла імла знизу
        int fogH = (int) (h * 0.36f);
        g.fillGradient(0, h - fogH, w, h, 0x00500810, BloodFx.a(0xFF500810, (0.16f + 0.05f * beat) * lf));

        // 3) віньєтка: неглибоке дихання (±0.06), без різких піків
        float va = (0.36f + 0.06f * beat) * lf;
        int depth = (int) (Math.min(w, h) * 0.28f);
        g.fillGradient(0, 0, w, depth, BloodFx.a(0xFF7A0A10, va), 0x007A0A10);
        g.fillGradient(0, h - depth, w, h, 0x007A0A10, BloodFx.a(0xFF7A0A10, va));
        for (int i = 0; i < depth; i += 3) {
            float k = 1f - i / (float) depth;
            int col = BloodFx.a(0xFF7A0A10, va * k * k * 0.9f);
            g.fill(i, 0, i + 3, h, col);
            g.fill(w - i - 3, 0, w - i, h, col);
        }

        // 4) кров
        curtain.render(g, w, h, now, lf);
    }
}
