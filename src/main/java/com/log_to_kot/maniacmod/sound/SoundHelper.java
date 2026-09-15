package com.log_to_kot.maniacmod.sound;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/**
 * Utility for playing sounds server-side.
 *
 * playGlobal  — всі гравці чують звук з однаковою гучністю (глобально)
 * playAt      — просторовий звук біля певної позиції
 */
public class SoundHelper {

    /**
     * Відтворює звук для кожного гравця глобально (повна гучність незалежно від відстані).
     * Використовується для важливих подій: початок гри, генератор запущено, тощо.
     */
    public static void playGlobal(List<ServerPlayer> players, SoundEvent sound,
                                   float volume, float pitch) {
        for (ServerPlayer player : players) {
            // Send sound packet directly to player's position — appears at full volume
            player.connection.send(new ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(sound),
                SoundSource.MASTER,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch,
                player.level().getRandom().nextLong()
            ));
        }
    }

    /** Overload with default pitch 1.0 */
    public static void playGlobal(List<ServerPlayer> players, SoundEvent sound, float volume) {
        playGlobal(players, sound, volume, 1.0f);
    }

    /**
     * Відтворює просторовий звук у конкретній позиції.
     * Тільки гравці поруч почують його.
     */
    public static void playAt(net.minecraft.server.level.ServerLevel level,
                               double x, double y, double z,
                               SoundEvent sound, SoundSource source,
                               float volume, float pitch) {
        level.playSound(null, x, y, z, sound, source, volume, pitch);
    }

    /** Звук тільки для одного гравця */
    public static void playTo(ServerPlayer player, SoundEvent sound,
                               float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
            net.minecraft.core.Holder.direct(sound),
            SoundSource.MASTER,
            player.getX(), player.getY(), player.getZ(),
            volume, pitch,
            player.level().getRandom().nextLong()
        ));
    }
}
