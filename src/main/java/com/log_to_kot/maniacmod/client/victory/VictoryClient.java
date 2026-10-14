package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.s2c.victory.VictoryStartPacket;
import dev.shaurmalib.forge.camera.replay.ReplayPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Клієнтська частина сцени перемоги маніяка: годинник, затемнення, титул «МАНІЯК ПЕРЕМІГ»,
 * блокування HUD/екранів. Камеру веде {@code ReplayPlayer} (команда сервера), тут лише кадр поверх.
 *
 * Хронологія (мс від пакета): fadeIn → чорний (чанки) до {@code fadeIn+load} → розтемнення REVEAL
 * → камера їде {@code move} → титул {@code title} → затемнення {@code fadeOut} → чорний, чекаємо
 * {@link #end()} → розтемнення лобі (FADE_IN) → IDLE. Усе відкладене ({@link #runWhenIdle}),
 * зокрема екран статистики, виконується після IDLE.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VictoryClient {

    public static final String OVERLAY_ID = "maniac_victory_scene";
    private static final int REVEAL_MS = 1000;
    /** Висота кожної кіно-смуги (частка висоти екрана) — тонкі, «слабкі». */
    private static final float BAR_FRACTION = 0.065f;
    private static final int BARS_IN_MS = 1600;
    private static final int LOBBY_FADE_MS = 1600;
    private static final long END_TIMEOUT_MS = 8000L;

    private enum Stage { IDLE, RUNNING, HOLD, FADE_IN }

    private static Stage stage = Stage.IDLE;
    private static long startNanos, fadeInNanos;
    private static int fadeInMs, loadMs, moveMs, titleMs, fadeOutMs;
    private static boolean endRequested;
    /** Яку сцену показуємо: перемога гравців (зелений титул) чи маніяка (червоний). */
    private static boolean survivorsWin;
    private static CameraType savedCamera;
    private static final List<Runnable> deferred = new ArrayList<>();

    private VictoryClient() {}

    // ── З пакетів ────────────────────────────────────────────────────────

    public static void start(VictoryStartPacket p) {
        Minecraft mc = Minecraft.getInstance();
        fadeInMs = p.fadeInMs();
        loadMs = p.loadMs();
        moveMs = p.moveMs();
        titleMs = p.titleMs();
        fadeOutMs = p.fadeOutMs();
        survivorsWin = p.survivorsWin();
        endRequested = false;
        VictoryTitleFx.reset();
        startNanos = System.nanoTime();
        stage = Stage.RUNNING;
        if (mc.screen != null) mc.setScreen(null);
        savedCamera = mc.options.getCameraType();
        UiAnim.playMenuWarn();
    }

    public static void end() {
        if (stage != Stage.IDLE) endRequested = true;
    }

    public static void onPhaseChanged(GamePhase next) {
        // Матч уже скинуто — відкладений екран статистики втратив сенс.
        if (next == GamePhase.LOBBY || next == GamePhase.RESET) deferred.clear();
        if (stage == Stage.IDLE) return;
        if (next == GamePhase.LOBBY || next == GamePhase.RESET) abort();
    }

    private static void abort() {
        stage = Stage.IDLE;
        VictoryTitleFx.reset();
        endRequested = false;
        ReplayPlayer.stop();
        restoreCamera();
    }

    private static void restoreCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (savedCamera != null && mc.options != null) mc.options.setCameraType(savedCamera);
        savedCamera = null;
    }

    /** Виконати, коли сцена повністю закінчилась (одразу, якщо її нема). */
    public static void runWhenIdle(Runnable action) {
        // FADE_IN — гравця вже телепортовано в лобі: екран статистики відкриваємо одразу,
        // не чекаючи кінця розтемнення (воно малюється під екраном).
        if (stage == Stage.IDLE || stage == Stage.FADE_IN) action.run(); else deferred.add(action);
    }

    private static int deferredWaitTicks;

    /** Виконати відкладене. Якщо поверх стоїть чужий екран довше 3 с — закриваємо його. */
    private static void flushDeferred(Minecraft mc) {
        if (deferred.isEmpty() || mc.player == null) { deferredWaitTicks = 0; return; }
        if (mc.screen != null) {
            if (++deferredWaitTicks < 60) return;
            mc.setScreen(null);
        }
        deferredWaitTicks = 0;
        List<Runnable> run = new ArrayList<>(deferred);
        deferred.clear();
        run.forEach(Runnable::run);
    }

    public static boolean isActive() { return stage != Stage.IDLE; }

    // ── Годинник ─────────────────────────────────────────────────────────

    private static long tMs() { return (System.nanoTime() - startNanos) / 1_000_000L; }
    private static long arriveMs()  { return fadeInMs + loadMs + moveMs; }
    private static long blackAtMs() { return arriveMs() + titleMs + fadeOutMs; }

    private static float cover(long t) {
        float a;
        long revealStart = fadeInMs + loadMs;
        if (stage == Stage.FADE_IN) {
            long f = (System.nanoTime() - fadeInNanos) / 1_000_000L;
            return 1f - UiAnim.easeInOutCubic(f / (float) LOBBY_FADE_MS);
        }
        if (t < fadeInMs) a = UiAnim.easeInOutCubic(t / (float) fadeInMs);
        else if (t < revealStart) a = 1f;
        else if (t < revealStart + REVEAL_MS) a = 1f - UiAnim.easeInOutCubic((t - revealStart) / (float) REVEAL_MS);
        else if (t < arriveMs() + titleMs) a = 0f;
        else if (t < blackAtMs()) a = UiAnim.easeInOutCubic((t - arriveMs() - titleMs) / (float) fadeOutMs);
        else a = 1f;
        return a;
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (stage == Stage.IDLE) {
            flushDeferred(mc);
            return;
        }
        if (mc.player == null || mc.level == null) { abort(); return; }
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) mc.options.setCameraType(CameraType.FIRST_PERSON);

        long t = tMs();
        switch (stage) {
            case RUNNING -> {
                VictoryTitleFx.tick(mc, t - arriveMs(), titleMs, fadeOutMs, survivorsWin);
                if (t >= blackAtMs()) stage = Stage.HOLD;
            }
            case HOLD -> {
                if (endRequested || t - blackAtMs() >= END_TIMEOUT_MS) {
                    stage = Stage.FADE_IN;
                    fadeInNanos = System.nanoTime();
                    UiAnim.playAppear();
                    flushDeferred(mc);   // статистика — одразу, як тільки гравця повернуло в лобі
                }
            }
            case FADE_IN -> {
                flushDeferred(mc);
                if ((System.nanoTime() - fadeInNanos) / 1_000_000L >= LOBBY_FADE_MS) {
                    stage = Stage.IDLE;
                    restoreCamera();
                }
            }
            default -> {}
        }
    }

    // ── Блокування ───────────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (stage == Stage.IDLE || stage == Stage.FADE_IN) return;
        if (OVERLAY_ID.equals(event.getOverlay().id().getPath())) return;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (stage == Stage.IDLE || stage == Stage.FADE_IN || event.getNewScreen() == null) return;
        event.setCanceled(true);
        if (event.getNewScreen() instanceof PauseScreen) Minecraft.getInstance().getSoundManager().resume();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stage = Stage.IDLE;
        endRequested = false;
        deferred.clear();
        savedCamera = null;
    }

    // ── Кадр ─────────────────────────────────────────────────────────────

    public static void render(GuiGraphics g, int w, int h) {
        if (stage == Stage.IDLE) return;
        long t = tMs();
        // Кіно-смуги: тонкі чорні зверху й знизу. Дві плавні анімації — виїзд на початку сцени
        // (поки камера починає рух) і від'їзд наприкінці (коли титул гасне). Без віньєтки й кольорових ліній.
        if (stage != Stage.FADE_IN) {
            long revealStart = fadeInMs + loadMs;
            float in = UiAnim.easeInOutCubic(UiAnim.clamp01((t - revealStart + 300) / (float) BARS_IN_MS));
            float outBars = 1f - UiAnim.easeInOutCubic(UiAnim.clamp01(
                (t - (arriveMs() + titleMs)) / (float) fadeOutMs));
            int bh = Math.round(h * BAR_FRACTION * in * outBars);
            if (bh > 0) {
                g.fill(0, 0, w, bh, 0xFF000000);
                g.fill(0, h - bh, w, h, 0xFF000000);
            }
        }

        // Титул: чіткий напис, прив'язаний до світової опори (див. VictoryTitleFx).
        long titleStart = arriveMs();
        if (stage == Stage.RUNNING && t >= titleStart && t < titleStart + titleMs + fadeOutMs) {
            VictoryTitleFx.draw(g, w, h, t - titleStart, titleMs, fadeOutMs, survivorsWin);
        }

        // У FADE_IN поверх уже стоїть екран статистики — затемнення малюємо над ним (onScreenRender).
        if (stage == Stage.FADE_IN && Minecraft.getInstance().screen != null) return;
        drawCover(g, w, h, t);
    }

    private static void drawCover(GuiGraphics g, int w, int h, long t) {
        float c = cover(t);
        if (c > 0.003f) g.fill(0, 0, w, h, BloodFx.a(0xFF000000, c));
    }

    /**
     * Після сцени екран статистики відкривається просто з чорного: чорний покрив малюється
     * НАД екраном і розтемнюється, тож статистика з'являється плавно, а не різко.
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (stage != Stage.FADE_IN) return;
        GuiGraphics g = event.getGuiGraphics();
        drawCover(g, event.getScreen().width, event.getScreen().height, tMs());
    }
}
