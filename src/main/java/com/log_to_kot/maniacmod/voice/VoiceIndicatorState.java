package com.log_to_kot.maniacmod.voice;

import java.util.function.BooleanSupplier;

/**
 * Клієнтський стан власного голосу (говорю / шепочу / мікрофон вимкнено / звук вимкнено / відключено)
 * для HUD-індикатора мода. Над головою інших гравців голосових значків НЕМАЄ — чужий стан не зберігається.
 *
 * ── Чому окремий клас без типів SVC ──────────────────────────────────
 * SVC опційний (див. voice/README.md). Малювання ({@code client/voice}) читає лише цей клас —
 * тому без SVC воно просто нічого не показує й не тягне класів SVC в завантажувач.
 * Наповнює стан {@link ManiacVoicePlugin} з публічних подій SVC API.
 *
 * ── Потоки ───────────────────────────────────────────────────────────
 * {@code markOwnTalking} викликає потік мікрофона SVC, читає головний потік клієнта — тому {@code volatile}.
 *
 * ── «Говорить» = недавній кадр ───────────────────────────────────────
 * Події приходять лише поки лунає голос; кінця передачі подія не несе. Тому «говорить» —
 * це кадр, що був не раніше {@link #TALK_HOLD_MS} тому.
 */
public final class VoiceIndicatorState {

    /** Стан власного індикатора в HUD. */
    public enum Own { NONE, TALK, WHISPER, MUTED, DEAFENED, DISCONNECTED }

    /** Скільки після останнього кадру вважаємо, що гравець ще говорить, мс. */
    private static final long TALK_HOLD_MS = 300L;
    /** Перші секунди після підключення «відключено» не показуємо (як штатний індикатор SVC). */
    private static final long STARTUP_GRACE_MS = 5000L;

    private static volatile BooleanSupplier muted = () -> false;
    private static volatile BooleanSupplier disabled = () -> false;
    private static volatile BooleanSupplier disconnected = () -> false;
    private static volatile long boundAt = 0L;
    /** «Сховати іконки» з меню/конфігу SVC: коли true — HUD-індикатор не малюється. */
    private static volatile BooleanSupplier iconsHidden = () -> false;

    private static volatile long ownTalkAt = 0L;
    private static volatile boolean ownWhisper = false;
    /** Гучність останнього кадру, 0..1 (див. {@link #levelOf}). */
    private static volatile float ownLevel = 0f;

    private VoiceIndicatorState() {}

    // ── Запис (потоки SVC) ───────────────────────────────────────────

    /** Підключає читання mute/deafen/disconnect власного клієнта SVC. */
    public static void bind(BooleanSupplier isMuted, BooleanSupplier isDisabled, BooleanSupplier isDisconnected) {
        muted = isMuted;
        disabled = isDisabled;
        disconnected = isDisconnected;
        boundAt = System.currentTimeMillis();
    }

    /** Підключає читання налаштування «сховати іконки» з конфігу SVC. */
    public static void bindIconsHidden(BooleanSupplier hidden) {
        iconsHidden = hidden;
    }

    /** Чи ховати HUD-індикатор (гравець увімкнув «Сховати іконки»). */
    public static boolean iconsHidden() {
        try {
            return iconsHidden.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Кадр власного мікрофона (16-bit PCM, ДО Opus і ДО голосу маньяка — обробник стоїть раніше за
     * {@code MANIAC_VOICE_PRIORITY}). Порожній кадр — кінець передачі, за нього «говорить» не вважаємо.
     */
    public static void markOwnTalking(boolean whisper, short[] rawAudio) {
        if (rawAudio == null || rawAudio.length == 0) return;
        ownWhisper = whisper;
        ownLevel = levelOf(rawAudio);
        ownTalkAt = System.currentTimeMillis();
    }

    /**
     * RMS кадру → 0..1 за шкалою децибел: −55 dBFS (тиша/шум) = 0, −15 dBFS (голосно) = 1.
     * Звичайна мова лягає приблизно в 0,5…0,9, тож іконка реагує на силу голосу, а не просто «є/нема».
     */
    static float levelOf(short[] pcm) {
        double sum = 0;
        for (short v : pcm) sum += (double) v * v;
        double rms = Math.sqrt(sum / pcm.length) / 32768.0;
        double db = 20.0 * Math.log10(Math.max(rms, 1e-5));
        return (float) Math.max(0.0, Math.min(1.0, (db + 55.0) / 40.0));
    }

    // ── Читання (головний потік клієнта) ─────────────────────────────

    /**
     * Пріоритет: відключено > вимкнено звук > говорю/шепочу > mute.
     * «Говорю» вище за mute навмисно: у режимі «натисни й говори» SVC ігнорує прапор mute й кадри йдуть,
     * тож якщо кадр реально пішов — гравець говорить, а не «вимкнений». У режимі за голосом при mute
     * кадрів нема, і показується mute.
     */
    public static Own own() {
        long now = System.currentTimeMillis();
        if (disconnected.getAsBoolean()) {
            return now - boundAt < STARTUP_GRACE_MS ? Own.NONE : Own.DISCONNECTED;
        }
        if (disabled.getAsBoolean()) return Own.DEAFENED;
        if (now - ownTalkAt < TALK_HOLD_MS) return ownWhisper ? Own.WHISPER : Own.TALK;
        if (muted.getAsBoolean()) return Own.MUTED;
        return Own.NONE;
    }

    /** Гучність власного голосу 0..1 з останнього кадру. Згладжує й затухає оверлей. */
    public static float ownLevel() {
        return ownLevel;
    }

    /** Вихід зі світу: стан не має переходити в наступну сесію. */
    public static void reset() {
        muted = () -> false;
        disabled = () -> false;
        disconnected = () -> false;
        boundAt = 0L;
        iconsHidden = () -> false;
        ownTalkAt = 0L;
        ownWhisper = false;
        ownLevel = 0f;
    }
}