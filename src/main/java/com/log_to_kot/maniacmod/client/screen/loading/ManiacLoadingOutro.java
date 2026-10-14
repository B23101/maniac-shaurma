package com.log_to_kot.maniacmod.client.screen.loading;

import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import dev.shaurmalib.forge.overlay.IntroOverlay;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Вихід із екрана заходу: коли ванільний екран зник, а гравець у світі, сцена
 * НЕ зникає різко і НЕ «роз'їжджається» ривком — вона плавно затемнюється до
 * чорного і тримає чорний, поки не стартує інтро «Команда Шаурма представляє» з ШАУРМА LIB
 * (воно саме починається з чорного — світ між ними не миготить). Якщо інтро вимкнене,
 * чорне коротко стоїть і плавно тане, відкриваючи світ.
 *
 * ── Як це влаштовано ─────────────────────────────────────────────────
 * Це HUD-оверлей (реєструється в {@code ClientSetup.onRegisterOverlays}
 * через {@code registerAboveAll}). Ванільні екрани на цей момент уже закриті,
 * тому у фазі затемнення оверлей сам ПЕРЕМАЛЬОВУЄ ту саму сцену (кадр
 * збігається з останнім кадром екрана — стику не видно) і накладає чорний
 * із наростаючою прозорістю. Далі — лише чорний, що зникає над світом.
 *
 * ── Коли запускається ────────────────────────────────────────────────
 * Лише на фронті «було екран заходу → тепер нема» при завантаженому світі і
 * лише якщо сцена малювалась щойно ({@link #FRESH_MS}). Невдале підключення
 * (світу нема) і виліт у меню оверлей не запускають. Якщо посеред виходу
 * знову зʼявився екран заходу (зміна виміру) — вихід скасовується.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacLoadingOutro {

    /** Сцена плавно тоне в чорне. */
    private static final long DARKEN_MS = 600L;
    /** Повний чорний — щоб перехід не відчувався «миготінням». */
    private static final long BLACK_MS = 150L;
    /** Найдовше чекаємо старту інтро бібліотеки на чорному (запобіжник). */
    private static final long INTRO_WAIT_MAX_MS = 2500L;
    /** Чорне тане, відкриваючи світ. */
    private static final long REVEAL_MS = 900L;
    private static final long FRESH_MS = 700L;

    private static long startedAt = -1L;
    private static boolean prevLoading;

    private ManiacLoadingOutro() {}

    /**
     * Ворота для інтро бібліотеки: поки сцена ще затемнюється, інтро стартувати не
     * може (інакше воно різко перекрило б напівзатемнену сцену чорним).
     */
    public static boolean introMayStart() {
        ManiacLoadingSession s = ManiacLoadingSession.last();
        if (s == null) return true;
        long now = Util.getMillis();
        if (startedAt >= 0L) return now - startedAt >= DARKEN_MS + BLACK_MS;
        return s.exiting || now - s.lastFrameAt() >= FRESH_MS;
    }

    public static void render(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        boolean loading = ManiacLoadingSession.isLoadingScreen(mc.screen);
        ManiacLoadingSession s = ManiacLoadingSession.last();

        if (startedAt < 0L) {
            if (prevLoading && !loading && mc.level != null && s != null && !s.exiting
                && now - s.lastFrameAt() < FRESH_MS) {
                startedAt = now;
                s.beginExit(now);
            }
            prevLoading = loading;
            return;
        }

        long el = now - startedAt;
        boolean waitIntro = IntroOverlay.isEnabled();
        long blackEnd = DARKEN_MS + (waitIntro ? INTRO_WAIT_MAX_MS : BLACK_MS);
        boolean introTook = waitIntro && el >= DARKEN_MS + BLACK_MS && IntroOverlay.isActive();
        if (s == null || loading || introTook || el > blackEnd + REVEAL_MS) {
            startedAt = -1L;
            prevLoading = loading;
            if (s != null && !loading) s.finish();
            return;
        }

        float black;
        if (el < DARKEN_MS) {
            s.tickExit(now);
            ManiacLoadingRenderer.render(g, w, h, s, -1, -1, null, true);
            black = UiAnim.easeInOutCubic(el / (float) DARKEN_MS);
        } else if (el < blackEnd) {
            black = 1f;
        } else {
            black = 1f - UiAnim.easeInOutCubic((el - blackEnd) / (float) REVEAL_MS);
        }
        if (black > 0.004f) g.fill(0, 0, w, h, BloodFx.a(0xFF000000, black));
    }
}
