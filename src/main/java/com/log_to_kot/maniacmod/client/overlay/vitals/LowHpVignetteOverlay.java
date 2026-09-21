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
 * Нижче {@link #VIGNETTE_THRESHOLD_FRACTION} (40% хп) вінʼєтка починає
 * проявлятись, на 0% хп альфа на максимумі {@link #MAX_ALPHA}. Квадрат
 * дробу (не лінійно) — той самий прийом, що {@code computeHeartbeat} у
 * {@code SurvivorModule}: останні відсотки перед смертю мають "бити"
 * помітніше, ніж перший поріг.
 */
@OnlyIn(Dist.CLIENT)
public final class LowHpVignetteOverlay {

    private LowHpVignetteOverlay() {}

    /** Частка хп, нижче якої вінʼєтка починає з'являтись. */
    private static final float VIGNETTE_THRESHOLD_FRACTION = 0.4f;

    /** Альфа (0..255) на самому краю екрана при 0 хп. Центр завжди лишається прозорим. */
    private static final int MAX_ALPHA = 160;

    /**
     * Колір вінʼєтки — світліший, насичений червоний (раніше 0x660000,
     * майже чорний на вигляд). Це саме той тон, що й читається як
     * «кров/небезпека», а не темна пляма по краях екрана.
     */
    private static final int RGB = 0xCC1414;

    /** Ширина (у пікселях GUI-простору) градієнтної смуги від кожного краю до прозорого центру. */
    private static final int EDGE_WIDTH = 90;

    /**
     * Ширина однієї вертикальної колонки (пікселі), якими емулюється
     * горизонтальний градієнт бічних смуг у {@link #renderHorizontalEdge}.
     * 2px непомітно оку й достатньо дешево (45 колонок на смугу
     * EDGE_WIDTH=90 замість 90 fill-викликів по пікселю).
     */
    private static final int COLUMN_STEP = 2;

    /**
     * Тривалість плавної появи вінʼєтки (мс) після перетину порогу.
     * Без цього альфа стрибає одразу на розрахункове значення в той
     * самий кадр, коли хп перетнув поріг — оку це читається як
     * "блимнуло", а не "з'явилось". Час іде від моменту, коли стан
     * "нижче порогу" почався (див. {@link #belowThresholdSince}).
     */
    private static final long FADE_IN_MS = 350;

    /** Частка хп, нижче якої вінʼєтка починає додатково БЛИМАТИ (критично мало). */
    private static final float BLINK_THRESHOLD_FRACTION = 0.12f;

    /** Період одного циклу блимання (мс): пройти тьмяно→яскраво→тьмяно. */
    private static final long BLINK_PERIOD_MS = 900;

    /**
     * Наскільки блимання може просадити альфу вниз відносно базового
     * значення (частка від base, 0..1). 0.5 = на найтьмянішій фазі
     * циклу вінʼєтка вдвічі слабша за розрахункову, а не гасне зовсім —
     * повне зникнення на біті виглядало б як "зникла", а не "пульсує".
     */
    private static final float BLINK_DEPTH = 0.5f;

    /**
     * Момент (мс, {@code System.currentTimeMillis()}), коли хп востаннє
     * ОПУСТИВСЯ нижче порогу. {@code -1} = зараз вище порогу (чи ще
     * жодного разу не опускався). Скидається назад у {@code -1}, щойно
     * хп повертається вище порогу (лікування) — наступне падіння під
     * поріг знову має плавно з'явитись, а не вискочити миттєво.
     */
    private static long belowThresholdSince = -1L;

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
        float intensity = t * t;

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
        if (fraction < BLINK_THRESHOLD_FRACTION && fadeIn >= 1f) {
            double phase = (now % BLINK_PERIOD_MS) / (double) BLINK_PERIOD_MS;
            float blink = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)); // 0..1..0, плавно
            float blinkMul = (1f - BLINK_DEPTH) + BLINK_DEPTH * blink;
            alphaF *= blinkMul;
        }

        int alpha = Math.round(alphaF);
        if (alpha <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        int edgeColor = (alpha << 24) | RGB;
        int transparent = 0x00000000;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1900); // під WorldTintOverlay (z=2000), над рештою HUD

        // Верх і низ: GuiGraphics.fillGradient інтерполює по Y, тож тут
        // це "з природи" саме той профіль, що треба — від краю екрана
        // (edgeColor) до центру (transparent).
        graphics.fillGradient(0, 0, w, EDGE_WIDTH, edgeColor, transparent);                 // згори
        graphics.fillGradient(0, h - EDGE_WIDTH, w, h, transparent, edgeColor);             // знизу

        // Ліво/право: fillGradient НЕ вміє інтерполювати по X (лише по
        // Y, X — просто межі прямокутника), тому один виклик тут не дає
        // "від краю до центру" — попередня версія малювала суцільний
        // верт. градієнт на всю ширину EDGE_WIDTH, БЕЗ затухання вбік,
        // що й виглядало як "зверху вниз" замість "від границі екрана".
        // Правильний горизонтальний градієнт емулюємо смугою вузьких
        // вертикальних колонок (fill, суцільний колір на кожну), у яких
        // альфа спадає від alpha на самій границі екрана до 0 біля
        // EDGE_WIDTH (центр смуги) — той самий профіль, що верх/низ,
        // тільки вісь X замість Y.
        renderHorizontalEdge(graphics, 0, h, alpha, edgeColor, true);   // зліва: сильно на x=0, згасає вправо
        renderHorizontalEdge(graphics, w, h, alpha, edgeColor, false);  // справа: сильно на x=w, згасає вліво

        graphics.pose().popPose();
    }

    /**
     * Малює бічну смугу шириною {@link #EDGE_WIDTH}, що згасає ВІД
     * границі екрана ДО центру (горизонтально) — ту саму роль для осі
     * X, яку {@code fillGradient} безкоштовно дає для осі Y.
     *
     * <p>{@code GuiGraphics.fillGradient} завжди інтерполює колір по Y;
     * замінити напрямок на X ним не можна, тому смуга розбивається на
     * COLUMN_STEP-піксельні вертикальні колонки, кожна — суцільний
     * {@code fill} із власною альфою. COLUMN_STEP=2 непомітний оку
     * (звичайний градієнт теж дискретний по пікселях), але вдвічі
     * дешевший за колонку на кожен піксель.</p>
     *
     * @param edgeX     координата X самої границі екрана (0 зліва, w справа)
     * @param h         висота екрана — смуга на всю висоту
     * @param baseAlpha альфа (0..255) рівно на границі екрана
     * @param edgeColor колір смуги БЕЗ альфи в старших бітах (RGB-частина читається з нього)
     * @param fromLeft  true — границя зліва (x зростає вглиб екрана); false — границя справа (x спадає)
     */
    private static void renderHorizontalEdge(GuiGraphics graphics, int edgeX, int h,
                                             int baseAlpha, int edgeColor, boolean fromLeft) {
        int rgb = edgeColor & 0x00FFFFFF;

        for (int offset = 0; offset < EDGE_WIDTH; offset += COLUMN_STEP) {
            // 0 на самій границі екрана, 1 біля центру (кінець смуги) —
            // лінійне загасання, той самий профіль, що fillGradient дає
            // верху/низу (лінійна інтерполяція colorFrom→colorTo по
            // довжині смуги) — усі чотири краї тьмяніють однаково.
            float t = (float) offset / EDGE_WIDTH;
            int alpha = Math.round(baseAlpha * (1f - t));
            if (alpha <= 0) break; // далі вглиб екрана — усе одно 0, решту колонок можна пропустити

            int columnColor = (alpha << 24) | rgb;
            int x0 = fromLeft ? offset : edgeX - offset - COLUMN_STEP;
            int x1 = x0 + COLUMN_STEP;
            graphics.fill(x0, 0, x1, h, columnColor);
        }
    }
}
