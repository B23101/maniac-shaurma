package com.log_to_kot.maniacmod.client.intro;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.WakeUpAnimation;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.intro.IntroTimeline;
import dev.shaurmalib.forge.camera.replay.ReplayPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
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
 * Клієнтський режисер кіно-вступу: годинник, стан, блокування вводу й HUD,
 * звукові сигнали. Малює {@link IntroOverlay}, музикою й ефектами керує
 * {@link IntroAudio}, камеру веде {@code ReplayPlayer} із ШАУРМА LIB.
 *
 * ── Стадії ───────────────────────────────────────────────────────────
 *  RUNNING   — від пакета IntroStart: затемнення → Replay + loader → затемнення
 *              → чорний. HUD прибрано, Tab/Esc/чат/екрани заблоковано.
 *  HOLD      — екран чорний, чекаємо IntroReveal від сервера (гравців
 *              телепортовано, контроль повернено) і догрузки чанків.
 *  FADE_IN   — розтемнення РАЗОМ із початком гри (пробудження). Музика гасне.
 *
 * Годинник — {@link System#nanoTime()}, тож вступ однаково йде, коли відкрито
 * будь-яке меню. Усі тривалості — у {@link IntroTimeline} (спільні з сервером).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class IntroClient {

    /** id оверлею вступу — єдиний, який не ховається разом із рештою HUD. */
    public static final String OVERLAY_ID = "maniac_match_intro";

    /** Скільки максимум чекати догрузки чанків навколо гравця перед розтемненням. */
    private static final long CHUNK_WAIT_MAX_MS = 1500L;
    /** Якщо IntroReveal так і не прийшов — розтемнюємо самі. */
    private static final long REVEAL_TIMEOUT_MS = 9000L;

    enum Stage { IDLE, RUNNING, HOLD, FADE_IN }

    private static Stage stage = Stage.IDLE;
    private static long startNanos;
    private static long fadeInNanos;
    private static int windowMs = 10_000;
    private static int tipMs = 5_000;
    private static boolean maniac;
    private static boolean revealRequested;

    private static IntroTips tips;
    private static final IntroAudio AUDIO = new IntroAudio();

    // Одноразові сигнали звуку.
    private static boolean cueBlackout, cueCameraIn, cueRiser;
    private static int lastTipSlot = -1;
    private static boolean resumeSoundNext;
    private static CameraType savedCamera;
    private static final List<Runnable> deferred = new ArrayList<>();

    private IntroClient() {}

    // ── Запуск / завершення (з пакетів) ──────────────────────────────────

    public static void start(int replayWindowMs, int tipIntervalMs, boolean isManiac) {
        Minecraft mc = Minecraft.getInstance();
        abortInternal(false);
        windowMs = replayWindowMs;
        tipMs = Math.max(1000, tipIntervalMs);
        maniac = isManiac;
        revealRequested = false;
        tips = new IntroTips();
        cueBlackout = cueCameraIn = cueRiser = false;
        lastTipSlot = -1;
        startNanos = System.nanoTime();
        stage = Stage.RUNNING;
        // Усе відкрите (меню налаштувань, чат) закриваємо — далі екрани заблоковано.
        if (mc.screen != null) mc.setScreen(null);
        savedCamera = mc.options.getCameraType();
        AUDIO.startMusic();
        AUDIO.cue(IntroAudio.DARKEN, 1f, 1f);
    }

    /** Сервер: гравці на місцях, контроль повернено. */
    public static void reveal() {
        if (stage != Stage.IDLE) revealRequested = true;
    }

    /** З {@code ClientMatchState.setPhase}: вихід із матчу/початок гри гасить вступ негайно. */
    public static void onPhaseChanged(GamePhase next) {
        if (stage == Stage.IDLE) return;
        switch (next) {
            case LOBBY, RESET, HUNT, POWERED, FINALE, ENDING -> abortInternal(true);
            default -> {}
        }
    }

    private static void abortInternal(boolean stopReplay) {
        if (stage == Stage.IDLE) return;
        stage = Stage.IDLE;
        revealRequested = false;
        AUDIO.stopMusic();
        if (stopReplay) ReplayPlayer.stop();
        restoreCamera();
    }

    private static void restoreCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (savedCamera != null && mc.options != null) mc.options.setCameraType(savedCamera);
        savedCamera = null;
    }

    // ── Запити від решти коду ────────────────────────────────────────────

    public static boolean isActive() { return stage != Stage.IDLE; }

    /** Екран чорний/затемнюється, гра ще не почалась: пробудження чекає розтемнення. */
    public static boolean isHoldingBlack() { return stage == Stage.RUNNING || stage == Stage.HOLD; }

    /** Гравець не має ні рухатись, ні крутити камеру (лише до повернення контролю). */
    public static boolean isBlockingInput() { return stage == Stage.RUNNING || stage == Stage.HOLD; }

    /** Екрани (Esc, чат, меню) заблоковано аж до кінця розтемнення. */
    public static boolean blocksScreens() { return stage != Stage.IDLE; }

    /** HUD прибрано повністю — до початку розтемнення. */
    static boolean hudHidden() { return stage == Stage.RUNNING || stage == Stage.HOLD; }

    /** Виконати, коли екрани розблокуються (наприклад, відкрити вибір пасток маньяка). */
    public static void runWhenUnblocked(Runnable action) {
        if (blocksScreens()) deferred.add(action); else action.run();
    }

    // ── Годинник (для оверлея) ───────────────────────────────────────────

    static long tMs() { return (System.nanoTime() - startNanos) / 1_000_000L; }
    static long fadeInMs() { return stage == Stage.FADE_IN ? (System.nanoTime() - fadeInNanos) / 1_000_000L : -1L; }
    static Stage stage() { return stage; }
    static int windowMs() { return windowMs; }
    static int tipMs() { return tipMs; }
    static boolean isManiac() { return maniac; }
    static IntroTips tips() {
        if (tips == null) tips = new IntroTips();
        return tips;
    }
    static long blackAtMs() { return IntroTimeline.CAMERA_START_MS + windowMs + IntroTimeline.DARKEN_OUT_MS; }

    /** Непрозорість чорного покриття 0..1 у момент {@code t} (з фінальним розтемненням). */
    static float coverAlpha(long t) {
        int w = windowMs;
        int revealStart = IntroTimeline.CAMERA_START_MS + IntroTimeline.PRE_HOLD_MS;
        float a;
        if (t < IntroTimeline.DARKEN_IN_MS) {
            a = UiAnim.easeInOutCubic(t / (float) IntroTimeline.DARKEN_IN_MS);
        } else if (t < revealStart) {
            a = 1f;
        } else if (t < revealStart + IntroTimeline.REVEAL_MS) {
            a = 1f - UiAnim.easeInOutCubic((t - revealStart) / (float) IntroTimeline.REVEAL_MS);
        } else if (t < w) {
            a = 0f;
        } else if (t < w + IntroTimeline.DARKEN_OUT_MS) {
            a = UiAnim.easeInOutCubic((t - w) / (float) IntroTimeline.DARKEN_OUT_MS);
        } else {
            a = 1f;
        }
        if (stage == Stage.FADE_IN) {
            a = 1f - UiAnim.easeInOutCubic(fadeInMs() / (float) IntroTimeline.FADE_IN_MS);
        }
        return Math.max(a, ReplayPlayer.dipAlpha());
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        if (stage == Stage.IDLE) {
            if (!deferred.isEmpty() && mc.player != null) {
                List<Runnable> run = new ArrayList<>(deferred);
                deferred.clear();
                run.forEach(Runnable::run);
            }
            return;
        }
        if (mc.player == null || mc.level == null) { abortInternal(true); return; }

        // Esc у одиночній грі ставив звуки на паузу ще до скасування екрана.
        if (resumeSoundNext) {
            resumeSoundNext = false;
            mc.getSoundManager().resume();
        }
        // F5 не має показувати камеру збоку.
        if (isBlockingInput() && mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }

        long t = tMs();
        switch (stage) {
            case RUNNING -> {
                if (!cueBlackout && t >= IntroTimeline.DARKEN_IN_MS) {
                    cueBlackout = true;
                    AUDIO.cue(IntroAudio.BLACKOUT, 1f, 1f);
                }
                int revealStart = IntroTimeline.CAMERA_START_MS + IntroTimeline.PRE_HOLD_MS;
                if (!cueCameraIn && t >= revealStart) {
                    cueCameraIn = true;
                    AUDIO.cue(IntroAudio.REVEAL_CAMERA, 1f, 1f);
                }
                if (!cueRiser && t >= windowMs - 600) {
                    cueRiser = true;
                    AUDIO.cue(IntroAudio.RISER, 1f, 1f);
                }
                int slot = tipSlot(t);
                if (slot != lastTipSlot) {
                    if (lastTipSlot >= 0) AUDIO.cue(IntroAudio.TIP_SWAP, 0.7f, 1f);
                    lastTipSlot = slot;
                }
                if (t >= blackAtMs()) stage = Stage.HOLD;
            }
            case HOLD -> {
                long sinceBlack = t - blackAtMs();
                boolean chunksReady = ReplayPlayer.isAreaLoaded(mc.player.getX(), mc.player.getZ(), 2);
                boolean canReveal = revealRequested && (chunksReady || sinceBlack >= CHUNK_WAIT_MAX_MS);
                if (canReveal || sinceBlack >= REVEAL_TIMEOUT_MS) beginFadeIn();
            }
            case FADE_IN -> {
                if (fadeInMs() >= IntroTimeline.FADE_IN_MS) {
                    stage = Stage.IDLE;
                    restoreCamera();
                }
            }
            default -> {}
        }
    }

    /** Слот поради за часом: перший слот стартує в момент, коли з'явився loader. */
    static int tipSlot(long t) {
        long local = Math.max(0L, t - IntroTimeline.CAMERA_START_MS);
        return (int) (local / tipMs);
    }

    private static void beginFadeIn() {
        stage = Stage.FADE_IN;
        fadeInNanos = System.nanoTime();
        // Музика гасне, коли все вже завантажено — разом із розтемненням.
        AUDIO.beginMusicFadeOut(IntroTimeline.FADE_IN_MS / 1000f + 0.8f);
        AUDIO.cue(IntroAudio.GAME_START, 1f, 1f);
        // Пробудження стартує в момент, коли екран починає світлішати.
        WakeUpAnimation.startDeferred();
    }

    // ── Блокування ───────────────────────────────────────────────────────

    /** Прибирає ВЕСЬ HUD (ванільний і модів), окрім оверлею вступу. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!hudHidden()) return;
        if (OVERLAY_ID.equals(event.getOverlay().id().getPath())) return;
        event.setCanceled(true);
    }

    /** Esc, чат, інвентар — жоден екран не відкриється, поки триває вступ. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!blocksScreens() || event.getNewScreen() == null) return;
        if (event.getNewScreen() instanceof PauseScreen) resumeSoundNext = true;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stage = Stage.IDLE;
        revealRequested = false;
        deferred.clear();
        AUDIO.stopMusic();
        savedCamera = null;
    }
}
