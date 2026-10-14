package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Дрібний спільний помічник для ПЛАВНИХ анімацій оверлеїв: функції
 * полегшення (ease) і згладжений frame-delta, плюс готові UI-звуки, щоб
 * кожен оверлей не тягнув власну копію одного й того самого коду.
 *
 * <p>Ідея: замість миттєвих стрибків (значення 0/1 щокадру) оверлей
 * тримає цільове значення й «доганяє» його експоненційно — око бачить
 * плавний рух. {@link #approach(float, float, float, float)} — той самий
 * кадро-незалежний підхід, що вже використовував {@code ManiacHotbarOverlay}
 * для рамки вибору (1 - e^(-швидкість·dt)), лише винесений в одне місце.</p>
 *
 * <p>Усі методи статичні й безстанові — стан (поточне/цільове значення)
 * тримає сам оверлей, цей клас лише рахує.</p>
 */
public final class UiAnim {

    private UiAnim() {}

    /** Згладжений час у секундах від попереднього кадру — стійкий до лаг-спайків (обмежений 0.1 с). */
    public static float frameDt(long[] lastNanos) {
        long now = System.nanoTime();
        float dt = lastNanos[0] == 0L ? 0f : (now - lastNanos[0]) / 1_000_000_000f;
        lastNanos[0] = now;
        return Math.min(dt, 0.1f);
    }

    /**
     * Експоненційне наближення {@code current} до {@code target} зі
     * швидкістю {@code speed} за кадр {@code dt}. Кадро-незалежне:
     * результат залежить від РЕАЛЬНОГО часу, а не від FPS.
     */
    public static float approach(float current, float target, float speed, float dt) {
        float factor = 1f - (float) Math.exp(-speed * dt);
        return current + (target - current) * factor;
    }

    /** Класичний ease-out (швидкий старт, плавне гальмування) для t у [0,1]. */
    public static float easeOutCubic(float t) {
        float x = clamp01(t);
        float inv = 1f - x;
        return 1f - inv * inv * inv;
    }

    /** Ease-in-out (плавний розгін і гальмування) для t у [0,1] — для «відкриття» панелей. */
    public static float easeInOutCubic(float t) {
        float x = clamp01(t);
        return x < 0.5f
                ? 4f * x * x * x
                : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** Легкий «відскок» (overshoot) — для появи елемента, що має «вискочити» й осісти. */
    public static float easeOutBack(float t, float overshoot) {
        float x = clamp01(t);
        float c3 = 1.70158f * overshoot;
        float c1 = c3 + 1f;
        float inv = x - 1f;
        return 1f + c1 * inv * inv * inv + c3 * inv * inv;
    }

    public static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ── UI-звуки (ванільні, щоб не тягнути власні .ogg) ────────────────

    /** Легкий клік — перемикання слота/таба/вибору. */
    public static void playClick() {
        play(SoundEvents.UI_BUTTON_CLICK.get(), 0.5f, 1.1f);
    }

    /** Готовність здібності/пастки — короткий високий «тіньк». */
    public static void playReady() {
        play(SoundEvents.NOTE_BLOCK_BELL.get(), 0.35f, 1.6f);
    }

    /** Поява важливого елемента — м'який «поп». */
    public static void playAppear() {
        play(SoundEvents.NOTE_BLOCK_PLING.get(), 0.6f, 1.2f);
    }

    /** Зникнення елемента — м'який «поп» назад. */
    public static void playDismiss() {
        play(SoundEvents.NOTE_BLOCK_PLING.get(), 0.5f, 0.7f);
    }

    /** Генератор у Tab дійшов до кінця — дзвінкий акорд із двох тонів. */
    public static void playTabGeneratorDone() {
        play(SoundEvents.NOTE_BLOCK_BELL.get(), 0.55f, 1.0f);
        play(SoundEvents.NOTE_BLOCK_BELL.get(), 0.40f, 1.5f);
    }

    /** Гравцю в Tab стало гірше (втрата хп/вибуття) — глухий тривожний «тум». */
    public static void playTabAlarm() {
        play(SoundEvents.NOTE_BLOCK_BASEDRUM.get(), 0.45f, 0.42f);
    }

    // ── Звуки ESC-меню та екрана оцінок (відкриття/закриття — без звуку) ─

    /** Наведення на кнопку меню — сухий низький «тік». */
    public static void playMenuHover() {
        play(SoundEvents.NOTE_BLOCK_HAT.get(), 0.30f, 0.55f);
    }

    /** Кнопка «небезпечної» дії (вихід) натиснута вперше — тривожний подвійний тон. */
    public static void playMenuWarn() {
        play(SoundEvents.NOTE_BLOCK_BASEDRUM.get(), 0.50f, 0.28f);
        play(SoundEvents.NOTE_BLOCK_BELL.get(), 0.25f, 0.55f);
    }

    /** Виставлена зірочка оцінки — короткий дзвінкий тон, вищий за вищої оцінки. */
    public static void playStar(float value) {
        play(SoundEvents.NOTE_BLOCK_BELL.get(), 0.35f, 0.7f + 0.22f * value);
    }

    private static void play(SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
