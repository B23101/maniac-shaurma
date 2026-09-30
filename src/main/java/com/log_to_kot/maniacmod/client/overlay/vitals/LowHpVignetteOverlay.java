package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Постійна червона вінʼєтка по краях екрана, що підсилюється що нижче
 * хп виживого — окремо від {@code SurvivorVitalsOverlay} (та панель
 * ЧИТАЄТЬСЯ, ця ВІДЧУВАЄТЬСЯ: гравець, що не дивиться на HUD-шкалу в
 * бою, усе одно бачить крайовим зором, що йому лишилось мало хп).
 *
 * ── Чому не {@code WorldTintOverlay}/{@code WorldTintSpec} ─────────────
 * WorldTintSpec — fade-in/hold/fade-out для РАЗОВИХ подій (перемога,
 * телепорт, "GO!"): один старт, один прогін за часом, потім сам
 * гасне. Тут навпаки — БЕЗПЕРЕРВНИЙ стан, прив'язаний не до часу від
 * якоїсь події, а до поточного {@code hp/maxHp} щокадру (може й
 * рухатись назад, коли гравця лікують). Пробувати перевикористати
 * time-based API під per-frame стан означало б або передзапускати
 * тінт щотік (він для цього не проєктувався — startedAt зʼїхав би),
 * або городити навколо нього другий шар стану. Простіше й чесніше —
 * окремий маленький рендер, що сам читає hp щокадру.
 *
 * ── Поріг і крива ────────────────────────────────────────────────────
 * Нижче {@link #VIGNETTE_THRESHOLD_FRACTION} (20% хп) вінʼєтка починає
 * проявлятись, на 0% хп альфа на максимумі {@link #MAX_ALPHA}. Квадрат
 * дробу (не лінійно) — той самий прийом, що {@code computeHeartbeat} у
 * {@code SurvivorModule}: останні відсотки перед смертю мають "бити"
 * помітніше, ніж перший поріг.
 */
@OnlyIn(Dist.CLIENT)
public final class LowHpVignetteOverlay {

    private LowHpVignetteOverlay() {}

    /** Частка хп, нижче якої вінʼєтка починає з'являтись. */
    private static final float VIGNETTE_THRESHOLD_FRACTION = 0.2f;

    /** Альфа (0..255) на самому краю екрана при 0 хп. Центр завжди лишається прозорим. */
    private static final int MAX_ALPHA = 225;

    /**
     * Колір вінʼєтки — світліший, насичений червоний (раніше 0x660000,
     * майже чорний на вигляд). Це саме той тон, що й читається як
     * «кров/небезпека», а не темна пляма по краях екрана.
     */
    private static final int RGB = 0xD20F0F;

    /**
     * Глибина віньєтки як ЧАСТКА екрана (раніше фіксовані 90 px, на GUI-масштабі
     * 2–3 це ледь помітна рамка). На порозі хп — {@link #DEPTH_MIN_SCALE} від
     * максимуму, на нулі хп — повна глибина: віньєтка не лише темнішає, а й
     * заповзає вглиб екрана.
     */
    private static final float DEPTH_X_FRACTION = 0.34f;
    private static final float DEPTH_Y_FRACTION = 0.46f;
    private static final float DEPTH_MIN_SCALE = 0.55f;

    /** Показник степеня спаду від краю: >1 — густо біля краю й плавно розчиняється вглиб. */
    private static final float FALLOFF_POWER = 1.7f;

    /** Крок смужки (px), якими емулюється градієнт. 2px непомітно оку й удвічі дешевше за піксель. */
    private static final int STEP = 2;

    /**
     * Тривалість плавної появи вінʼєтки (мс) після перетину порогу.
     * Без цього альфа стрибає одразу на розрахункове значення в той
     * самий кадр, коли хп перетнув поріг — оку це читається як
     * "блимнуло", а не "з'явилось". Час іде від моменту, коли стан
     * "нижче порогу" почався (див. {@link #belowThresholdSince}).
     */
    private static final long FADE_IN_MS = 350;

    /** Частка хп, нижче якої вінʼєтка починає додатково БЛИМАТИ (критично мало). */
    private static final float BLINK_THRESHOLD_FRACTION = 0.10f;

    /** Період одного циклу блимання (мс): пройти тьмяно→яскраво→тьмяно. */
    private static final long BLINK_PERIOD_MS = 900;

    /**
     * Наскільки блимання може просадити альфу вниз відносно базового
     * значення (частка від base, 0..1). 0.5 = на найтьмянішій фазі
     * циклу вінʼєтка вдвічі слабша за розрахункову, а не гасне зовсім —
     * повне зникнення на біті виглядало б як "зникла", а не "пульсує".
     */
    private static final float BLINK_DEPTH = 0.5f;

    /** Глибина повільної пульсації віньєтки (частка альфи): звичайна й критична зона. */
    private static final float PULSE_DEPTH = 0.12f;
    private static final float PULSE_DEPTH_CRITICAL = 0.22f;

    /**
     * Момент (мс, {@code System.currentTimeMillis()}), коли хп востаннє
     * ОПУСТИВСЯ нижче порогу. {@code -1} = зараз вище порогу (чи ще
     * жодного разу не опускався). Скидається назад у {@code -1}, щойно
     * хп повертається вище порогу (лікування) — наступне падіння під
     * поріг знову має плавно з'явитись, а не вискочити миттєво.
     */
    private static long belowThresholdSince = -1L;

    /**
     * Наскільки мало хп у власного виживого: 0 — вище порога (або не
     * виживий / лежить / без vitals), 1 — нуль хп. Ту саму шкалу читає
     * {@code HeartbeatSoundHandler}, щоб серце в грудях і віньєтка
     * вмикались від одного й того самого порога.
     */
    public static float lowHpSeverity() {
        if (!ClientMatchState.isSurvivor()) return 0f;
        SurvivorState state = ClientMatchState.survivorState();
        if (state != SurvivorState.HEALTHY && state != SurvivorState.BROKEN_LEG) return 0f;
        int maxHp = ClientMatchState.maxHp();
        if (maxHp <= 0) return 0f;
        float fraction = Math.max(0f, Math.min(1f, (float) ClientMatchState.hp() / maxHp));
        if (fraction >= VIGNETTE_THRESHOLD_FRACTION) return 0f;
        return 1f - fraction / VIGNETTE_THRESHOLD_FRACTION;
    }

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor()) return;

        // Непритомний/лежачий/вибулий — своя візуальна мова (поза,
        // чорно-білий екран смерті тощо, якщо з'явиться); вінʼєтка хп
        // тут була б зайвим шаром поверх уже зрозумілого "ти лежиш".
        SurvivorState state = ClientMatchState.survivorState();
        if (state != SurvivorState.HEALTHY && state != SurvivorState.BROKEN_LEG) return;

        int hp = ClientMatchState.hp();
        int maxHp = ClientMatchState.maxHp();
        if (maxHp <= 0) { belowThresholdSince = -1L; return; } // vitals ще не прийшли цього матчу

        float fraction = Math.max(0f, Math.min(1f, (float) hp / maxHp));
        if (fraction >= VIGNETTE_THRESHOLD_FRACTION) {
            belowThresholdSince = -1L; // вилікували назад вище порогу — наступний раз знову fade-in
            return;
        }

        long now = System.currentTimeMillis();
        if (belowThresholdSince < 0) belowThresholdSince = now;

        // 0 на порозі, 1 на нулі хп — і зведено в квадрат, щоб перші
        // відсотки під порогом ледь відчувались, а останні перед 0 —
        // помітно.
        float t = 1f - (fraction / VIGNETTE_THRESHOLD_FRACTION);
        // Було t² — на середині зони (10% хп) віньєтка давала лише чверть сили й майже не
        // читалась. Степінь 1.35 нарощує її раніше, лишаючи різкий пік перед нулем.
        float intensity = (float) Math.pow(t, 1.35);

        float base = MAX_ALPHA * intensity;

        // Плавна поява: перші FADE_IN_MS після перетину порогу альфа
        // росте від 0 до base, а не стрибає одразу — вінʼєтка "проявляється",
        // а не миготить у кадр падіння хп.
        long sinceThreshold = now - belowThresholdSince;
        float fadeIn = sinceThreshold >= FADE_IN_MS
            ? 1f
            : Math.max(0f, sinceThreshold / (float) FADE_IN_MS);
        float alphaF = base * fadeIn;

        // Критично мало хп: додаємо блимання поверх fade-in — синусоїда
        // 0..1 по BLINK_PERIOD_MS, що просідає альфу до BLINK_DEPTH від
        // базової на найтьмянішій фазі. Активується лише під окремим,
        // нижчим порогом, щоб "просто мало хп" не мигтіло — блимає лише
        // справжня критична зона.
        if (fadeIn >= 1f) {
            // БАГ (занадто різке блимання): раніше альфа стрибала в такт
            // серцю подвійним «тук-тук» з глибиною 38–62% — краї екрана
            // різко спалахували двічі за цикл і дратували. Тепер це
            // повільне «дихання»: одна плавна синусоїда, глибина лише
            // 12–22%, тож віньєтка ледь-ледь пульсує, а не блимає.
            float crit = 1f - Math.min(1f, fraction / VIGNETTE_THRESHOLD_FRACTION);
            long period = Math.round(2600 - 900 * crit);
            float wave = 0.5f + 0.5f * (float) Math.sin((now % period) / (double) period * Math.PI * 2.0);
            float depth = fraction < BLINK_THRESHOLD_FRACTION ? PULSE_DEPTH_CRITICAL : PULSE_DEPTH;
            alphaF *= 1f - depth * wave;
        }

        int alpha = Math.min(255, Math.round(alphaF));
        if (alpha <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // Чим менше хп, тим глибше віньєтка заходить в екран.
        float depthScale = DEPTH_MIN_SCALE + (1f - DEPTH_MIN_SCALE) * t;
        int depthX = Math.round(w * DEPTH_X_FRACTION * depthScale);
        int depthY = Math.round(h * DEPTH_Y_FRACTION * depthScale);

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1900); // під WorldTintOverlay (z=2000), над рештою HUD

        // Усі чотири краї — однаковий профіль (fillGradient вміє лише по Y і лінійно,
        // тому обходимось вузькими смужками): альфа від краю екрана до нуля за
        // FALLOFF_POWER. Кути перекриваються й виходять темнішими — це і є віньєтка.
        renderEdge(graphics, w, h, depthX, alpha, Edge.LEFT);
        renderEdge(graphics, w, h, depthX, alpha, Edge.RIGHT);
        renderEdge(graphics, w, h, depthY, alpha, Edge.TOP);
        renderEdge(graphics, w, h, depthY, alpha, Edge.BOTTOM);

        graphics.pose().popPose();
    }

    private enum Edge { LEFT, RIGHT, TOP, BOTTOM }

    /**
     * Смуга завглибшки {@code depth} від краю екрана; альфа {@code baseAlpha}
     * на самому краї, далі спадає за {@code (1 - t)^FALLOFF_POWER} до 0.
     */
    private static void renderEdge(GuiGraphics g, int w, int h, int depth, int baseAlpha, Edge edge) {
        for (int offset = 0; offset < depth; offset += STEP) {
            float t = (float) offset / depth;
            int a = Math.round(baseAlpha * (float) Math.pow(1f - t, FALLOFF_POWER));
            if (a <= 0) break;
            int color = (a << 24) | RGB;
            int end = Math.min(offset + STEP, depth);
            switch (edge) {
                case LEFT   -> g.fill(offset, 0, end, h, color);
                case RIGHT  -> g.fill(w - end, 0, w - offset, h, color);
                case TOP    -> g.fill(0, offset, w, end, color);
                case BOTTOM -> g.fill(0, h - end, w, h - offset, color);
            }
        }
    }
}
