package com.log_to_kot.maniacmod.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Один "живий" клієнтський прогін звуку заливу пального — той самий
 * принцип, що {@link GeneratorLoopClientSound} (детальне пояснення "чому
 * саме клієнт, а не сервер" — там), тільки з двома додатковими
 * особливостями, властивими саме заливу:
 *
 * ── Три семпли по черзі, а не один зациклений ──────────────────────────
 * {@code fuel_fill} у {@code sounds.json} — одна подія з трьома файлами
 * ({@code fuel_fill_1/2/3}); ванільний звуковий рушій сам обирає
 * випадковий варіант щоразу, коли ЗАВОДИТЬСЯ НОВИЙ інстанс на цю подію
 * (той самий механізм, що раніше давав різноманіття через окремі
 * серверні пакети). Тут кожен новий інстанс {@link GeneratorFuelClientSound}
 * так само отримує свій випадковий варіант автоматично — жодного
 * ручного вибору з трьох не потрібно, досить заводити новий інстанс,
 * коли попередній повністю доспівав.
 *
 * ── Коли заводити наступний семпл ───────────────────────────────────────
 * {@code fuel_fill} — три файли РІЗНОЇ довжини, і рушій сам вирішує,
 * який саме зіграє в кожному інстансі — тому наперед невідомо, коли
 * САМЕ ЦЕЙ інстанс замовкне. Використовується найкоротший з трьох
 * ({@link #NATURAL_LENGTH_TICKS}, той самий підхід, що раніше був на
 * сервері через {@code OggSoundLength}): хендлер заводить наступний
 * інстанс рівно тоді, коли спливає ця мінімальна тривалість, тобто
 * трохи РАНІШЕ за фактичний кінець довших варіантів — короткий
 * накладення двох семплів непомітне на слух і значно краще за паузу
 * тиші, якою б обернулася протилежна помилка.
 *
 * ── Плавне згасання наприкінці замість обриву ──────────────────────────
 * Коли гравець відпускає Shift+ПКМ (чи заряд каністри скінчився), не
 * можна просто миттєво заглушити семпл, що вже грає — вийде той самий
 * різкий обрив, з якого й почалась вся ця історія. Замість цього
 * {@link #release()} переводить інстанс у режим {@code releasing}: замість
 * заводити наступний семпл після цього, {@link #tick()} лінійно веде
 * гучність поточного до нуля за {@link #RELEASE_FADE_TICKS} тіків, і
 * лише тоді сам себе позначає завершеним ({@link #isStopped()}) — той
 * самий семпл дограє своє природне закінчення, просто тихіше й тихіше,
 * а не обривається серед звуку.
 */
public final class GeneratorFuelClientSound extends AbstractTickableSoundInstance {

    /** Скільки тіків триває згасання після відпускання — досить швидко, щоб не тягнути залив ще на секунду. */
    private static final int RELEASE_FADE_TICKS = 6;

    /** Найкоротший з трьох файлів fuel_fill_1/2/3 — той самий підхід, що раніше мав серверний GeneratorFuelSoundscape. */
    private static final int NATURAL_LENGTH_TICKS = Math.max(1, Math.min(
        com.log_to_kot.maniacmod.sound.OggSoundLength.ticksOf("fuel_fill_1"),
        Math.min(com.log_to_kot.maniacmod.sound.OggSoundLength.ticksOf("fuel_fill_2"),
            com.log_to_kot.maniacmod.sound.OggSoundLength.ticksOf("fuel_fill_3"))));

    private final float targetVolume;
    private boolean releasing = false;
    private int releaseTicksLeft = RELEASE_FADE_TICKS;
    private int ticksAlive = 0;
    private boolean stopped = false;

    public GeneratorFuelClientSound(Holder<SoundEvent> soundHolder, double x, double y, double z, float volume) {
        super(soundHolder.value(), SoundSource.BLOCKS, net.minecraft.util.RandomSource.create());
        this.targetVolume = volume;
        this.x = x;
        this.y = y;
        this.z = z;
        this.looping = false; // кожен прогін — один випадковий варіант рівно один раз, наступний заводить хендлер
        this.delay = 0;
        this.volume = volume;
        this.pitch = 1.0f;
        this.attenuation = Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        if (stopped) {
            this.volume = 0f;
            return;
        }
        ticksAlive++;
        if (releasing) {
            releaseTicksLeft--;
            float t = Math.max(0f, (float) releaseTicksLeft / RELEASE_FADE_TICKS);
            this.volume = targetVolume * t;
            if (releaseTicksLeft <= 0) {
                stopped = true;
            }
        }
    }

    /** true — мінімальна тривалість найкоротшого з трьох файлів уже спливла: хендлеру час завести наступний прогін. */
    public boolean naturalLengthElapsed() {
        return ticksAlive >= NATURAL_LENGTH_TICKS;
    }

    /**
     * Гравець відпустив залив (чи каністра спорожніла) — почати плавне
     * згасання поточного семпла замість наступного прогону. Викликати
     * лише один раз за інстанс; повторні виклики ігноруються, щоб
     * послідовні кадри "ще відпущено" не перезапускали відлік згасання.
     */
    public void release() {
        if (!releasing) releasing = true;
    }

    /** true — цей інстанс повністю відіграв своє (природно чи через fade) і більше не потребує уваги хендлера. */
    public boolean isStopped() {
        return stopped;
    }

    /** true — уже в процесі згасання (щоб хендлер не плутав "ще грає нормально" з "уже гасне"). */
    public boolean isReleasing() {
        return releasing;
    }

    @Override
    public boolean canPlaySound() {
        return !stopped;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
