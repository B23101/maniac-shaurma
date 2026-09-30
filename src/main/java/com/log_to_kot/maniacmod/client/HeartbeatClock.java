package com.log_to_kot.maniacmod.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * ЄДИНИЙ годинник серцебиття виживого — і звук ({@link HeartbeatSoundHandler}),
 * і пульсація серця в {@code SurvivorVitalsOverlay} читають ЗВІДСИ, тож
 * б'ються завжди в такт.
 *
 * <h3>Що було не так раніше</h3>
 * <ul>
 *   <li><b>Оверлей</b> рахував фазу як {@code (now % periodMs) / periodMs}, а
 *       {@code periodMs} залежить від {@code heartbeat}, який приходить від
 *       сервера щотік. Щойно період змінювався — {@code now % period}
 *       стрибала в іншу точку циклу, і серце смикалось у випадкові боки.
 *       Форма пульсу була "пилою" (різкий підйом і лінійний спад) — один
 *       удар замість "тук-тук".</li>
 *   <li><b>Звук</b> ішов від {@code ClientTickEvent} (кроки по 50 мс) із
 *       періодом до 260 мс, а другий удар ставився за 250 мс ДО кінця
 *       циклу — тобто через 10 мс після першого. Обидва семпли по 300 мс,
 *       тож впритул до маньяка вони накладались у кашу, ще й із джитером
 *       ±50 мс.</li>
 * </ul>
 *
 * <h3>Як тепер</h3>
 * <ul>
 *   <li>Фаза ІНТЕГРУЄТЬСЯ покадрово ({@code phase += dt / period}) — зміна
 *       періоду міняє лише швидкість, а не "перескакує" фазу.</li>
 *   <li>Вхідне значення згладжується, тож серце прискорюється плавно, а не
 *       ривками по тікових пакетах.</li>
 *   <li>Період {@link #PERIOD_CALM_MS} → {@link #PERIOD_CLOSE_MS}: найшвидше —
 *       ~125 уд/хв. Другий удар ("dub") стоїть на {@link #DUB_AT} частці
 *       циклу, тож проміжок між ударами ≥ 170 мс навіть впритул — семпли
 *       (300 мс) більше не зливаються.</li>
 *   <li>Візуальний {@link #pulse()} — два удари з м'яким підйомом і
 *       експоненційним спадом (велика "lub" + менша "dub"), безперервний
 *       на стику циклів.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class HeartbeatClock {

    /** Період удару, коли маньяка нема поруч (візуальний "спокійний" пульс). */
    public static final float PERIOD_CALM_MS = 1100f;
    /** Період удару впритул до маньяка (~125 уд/хв). */
    public static final float PERIOD_CLOSE_MS = 480f;

    /** Де в циклі (частка 0..1) лежить другий удар. */
    public static final float DUB_AT = 0.36f;
    /** Другий удар слабший за перший. */
    private static final float DUB_STRENGTH = 0.72f;

    /** Швидкість згладжування вхідного heartbeat, 1/с (стала часу ≈ 0.17 с). */
    private static final float SMOOTH_RATE = 6f;

    /** Бітові події, які повертає {@link #update}. */
    public static final int EVENT_LUB = 1;
    public static final int EVENT_DUB = 2;

    private static float phase = 0f;
    private static float smoothed = 0f;
    private static boolean running = false;

    private HeartbeatClock() {}

    /** Гравець не виживий / вийшов зі світу — наступний запуск стартує з чистого циклу. */
    public static void reset() {
        phase = 0f;
        smoothed = 0f;
        running = false;
    }

    /**
     * Крок годинника. Викликати один раз на кадр.
     *
     * @param dtSeconds крок часу (обмежується зверху, щоб пауза/лаг не вистрілили серією ударів)
     * @param target    сире {@code ClientMatchState.heartbeat()} 0..1
     * @return маска {@link #EVENT_LUB}/{@link #EVENT_DUB} — які удари припали на цей крок
     */
    public static int update(float dtSeconds, float target) {
        float dt = Math.max(0f, Math.min(0.1f, dtSeconds));
        target = Math.max(0f, Math.min(1f, target));

        int events = 0;
        if (!running) {
            // Перший кадр: цикл починається з удару, як і в оригіналі.
            running = true;
            phase = 0f;
            smoothed = target;
            return EVENT_LUB;
        }

        smoothed += (target - smoothed) * (1f - (float) Math.exp(-SMOOTH_RATE * dt));
        if (Math.abs(target - smoothed) < 0.001f) smoothed = target;

        float prev = phase;
        phase += dt * 1000f / periodMs(smoothed);

        if (phase >= 1f) {
            phase -= (float) Math.floor(phase);
            events |= EVENT_LUB;
            if (phase >= DUB_AT) events |= EVENT_DUB; // лише при дуже великому dt
        } else if (prev < DUB_AT && phase >= DUB_AT) {
            events |= EVENT_DUB;
        }
        return events;
    }

    /** Період циклу (мс) для рівня близькості 0..1. */
    public static float periodMs(float level) {
        float l = Math.max(0f, Math.min(1f, level));
        return PERIOD_CALM_MS + (PERIOD_CLOSE_MS - PERIOD_CALM_MS) * l;
    }

    /** Згладжений рівень близькості маньяка 0..1 — саме ним міряються гучність і амплітуда. */
    public static float level() {
        return smoothed;
    }

    /** Фаза поточного циклу 0..1; 0 = момент першого удару. */
    public static float phase() {
        return phase;
    }

    /** Скільки мс минуло від першого удару поточного циклу. */
    public static float msSinceLub() {
        return phase * periodMs(smoothed);
    }

    /**
     * Форма пульсу 0..1 для візуалу: перший удар — повна сила, другий — {@link #DUB_STRENGTH}.
     * Кожен удар — швидкий синусний підйом і експоненційний спад; хвіст другого удару
     * дотягується через межу циклу, тож кривая безперервна.
     */
    public static float pulse() {
        float period = periodMs(smoothed);
        float t = phase * period;
        float dubT = DUB_AT * period;

        float lub = envelope(t, 42f, 135f);
        float dub = DUB_STRENGTH * envelope(t - dubT, 36f, 115f);
        float dubTail = DUB_STRENGTH * envelope(t + period - dubT, 36f, 115f);
        return Math.max(lub, Math.max(dub, dubTail));
    }

    /** Огинаюча одного удару: sin-підйом за {@code attackMs}, далі експонента з сталою {@code decayMs}. */
    private static float envelope(float x, float attackMs, float decayMs) {
        if (x < 0f) return 0f;
        if (x < attackMs) return (float) Math.sin(x / attackMs * Math.PI * 0.5);
        return (float) Math.exp(-(x - attackMs) / decayMs);
    }
}
