package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.registry.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * Один "живий" клієнтський прогін гулу генератора з реальним плавним
 * fade гучності — на відміну від серверного підходу
 * ({@code GeneratorLoopSoundscape}), який мусив пересилати НОВИЙ
 * {@code ClientboundSoundPacket} щоразу, як гучність мінялась.
 *
 * ── Чому серверний fade плавно наростав, але не плавно згасав ─────────
 * У Minecraft {@code ClientboundSoundPacket} — це завжди СТАРТ нового
 * семпла з нуля, він не вміє "підкрутити гучність уже граючому звуку".
 * Тому кожен пакет із новою гучністю неминуче обриває попередній
 * прогін і починає інший заново. При наближенні це на слух губилося —
 * повтор природного лупа й так регулярно перезапускає файл, тому
 * додатковий рестарт зі зростаючою гучністю збігався з очікуваним
 * швом. При віддаленні ж кожна найменша зміна дистанції одразу слала
 * НОВИЙ рестарт із тихішою гучністю — і замість плавного спаду виходила
 * серія маленьких шматків звуку, що обриваються один за одним, чутна
 * як тріск/стрибки, а не fade.
 *
 * ── Рішення: один SoundInstance, що сам собі тримає гучність ──────────
 * {@link AbstractTickableSoundInstance#tick()} викликається рушієм
 * щокадру для звуку, що вже грає, і саме тут можна змінювати
 * {@link #volume} на льоту без жодного рестарту семпла — рушій сам
 * плавно змішує нову гучність у вже запущений потік. Заводиться і
 * гаситься цей інстанс клієнтським хендлером
 * ({@code GeneratorLoopClientSoundHandler}) на основі
 * {@code GeneratorEntity.isActive()} — це поле й так уже
 * синхронізується клієнту безкоштовно через {@code SynchedEntityData},
 * тому жодних додаткових мережевих пакетів для самого лупа більше не
 * треба: ні для старту, ні для стопу, ні для гучності.
 */
public final class GeneratorLoopClientSound extends AbstractTickableSoundInstance {

    /** Джерело гучності — сам клас лише читає це поле щокадру, рахує позицію генератора хендлер. */
    private final GeneratorLoopVolumeSource volumeSource;

    private boolean stopped = false;

    public GeneratorLoopClientSound(double x, double y, double z, GeneratorLoopVolumeSource volumeSource) {
        super(ModSounds.GENERATOR_LOOP.get(), SoundSource.BLOCKS, net.minecraft.util.RandomSource.create());
        this.volumeSource = volumeSource;
        this.x = x;
        this.y = y;
        this.z = z;
        this.looping = true;
        this.delay = 0;
        this.volume = volumeSource.currentVolume();
        this.pitch = 1.0f;
        this.attenuation = Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        if (stopped) {
            this.volume = 0f;
            return;
        }
        // Тут і відбувається сам fade: щокадру нова гучність від
        // хендлера (порахована з поточної дистанції гравця) плавно
        // приймається рушієм у вже граючий звук — без рестарту семпла
        // в жодну зі сторін, на відміну від старого серверного підходу.
        this.volume = volumeSource.currentVolume();
    }

    /** Викликається хендлером, коли генератор деактивується або гравець виходить з радіуса зовсім. */
    public void markStopped() {
        this.stopped = true;
    }

    @Override
    public boolean canPlaySound() {
        return !stopped;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    /** Постачальник поточної цільової гучності — рахує її хендлер із живої дистанції гравця щокадру. */
    public interface GeneratorLoopVolumeSource {
        float currentVolume();
    }
}
