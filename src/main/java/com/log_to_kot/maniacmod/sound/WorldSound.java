package com.log_to_kot.maniacmod.sound;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Звук у світі, який грає СЕРВЕР — у своєму радіусі чутності.
 *
 * ── Чому не {@code Level#playSound} ──────────────────────────────────
 * Ванільний метод не має параметра радіуса. Він розсилає звук за
 * жорстким правилом {@code volume > 1 ? 16 * volume : 16} блоків, тож
 * найменша зона — 16 блоків, і зробити звук чутним ЛИШЕ на 10 чи 5
 * блоків через нього неможливо. Тут звук іде тим самим ванільним
 * пакетом {@link ClientboundSoundPacket} (клієнт бачить його як
 * ЗВИЧАЙНИЙ звук світу — з загасанням за відстанню й панорамою), але
 * розсилається через {@code PlayerList#broadcast}, у якого радіус —
 * параметр.
 *
 * ── Чому не {@code SoundCenter} із shaurma-lib ───────────────────────
 * SoundCenter грає звук ЛОКАЛЬНО, на клієнті, який його викликав. Для
 * звуку генератора це неправильно: він мусить бути чутний усім, хто
 * поруч (і маньяку теж), іти З ТОЧКИ генератора й загасати з
 * відстанню — тобто бути звуком світу, а не «подією в інтерфейсі
 * одного гравця». Тому джерело звуку — сервер.
 *
 * ── Чому {@code SoundSource} — параметр ──────────────────────────────
 * Категорія визначає, яким ванільним слайдером гучності гравець може
 * приглушити звук. Механізми (генератор) — це {@link SoundSource#BLOCKS},
 * тож гравець, якого дратує гул, зменшує «Блоки», а не вимикає всі
 * звуки гри.
 */
public final class WorldSound {

    private WorldSound() {}

    /**
     * Програти звук усім, хто зараз у радіусі {@code radiusBlocks} від
     * точки {@code at}, в тому самому вимірі.
     *
     * @param level       рівень сервера; {@code null} — тихо нічого не
     *                    робимо (викликачі беруть рівень з онлайн-гравця,
     *                    а матч може тікати й без них)
     * @param sound       подія з {@code ModSounds}
     * @param at          точка світу, З ЯКОЇ чути звук (центр генератора)
     * @param radiusBlocks радіус чутності у блоках — умикає/вимикає звук
     * @param volume      гучність у точці (1.0 = як звичайний звук блока)
     * @param pitch       висота; 1.0 — як у файлі
     */
    public static void playInRadius(ServerLevel level, SoundEvent sound, SoundSource source,
                                    Vec3 at, double radiusBlocks, float volume, float pitch) {
        if (level == null) return;

        // Холдер, а не SoundEvent: саме так звук їде в пакеті (клієнт
        // знаходить його за id у своєму реєстрі). Нема холдера — звук не
        // зареєстровано; це тиха відмова без крашу посеред матчу.
        Holder<SoundEvent> holder = ForgeRegistries.SOUND_EVENTS.getHolder(sound).orElse(null);
        if (holder == null) return;

        level.getServer().getPlayerList().broadcast(
            null, at.x, at.y, at.z, radiusBlocks, level.dimension(),
            new ClientboundSoundPacket(holder, source, at.x, at.y, at.z,
                volume, pitch, level.random.nextLong()));
    }
}
