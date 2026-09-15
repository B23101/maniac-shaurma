package com.log_to_kot.maniacmod.game;

import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.network.ModMessages;
import com.log_to_kot.maniacmod.config.ManiacConfig;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.sound.SoundHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Зберігає параметри майбутньої гри поки гравець-маньяк вибирає персонажа
 * в ManiacSelectScreen.
 *
 * Флоу:
 *  1. /maniac start → відлік 10 сек
 *  2. Якщо maniacTypeMode = MENU:
 *       a. Надсилаємо OpenManiacSelectPacket гравцю-маньяку
 *       b. Зберігаємо всі параметри тут
 *       c. Показуємо іншим: "Маньяк вибирає персонажа..."
 *  3. Гравець вибирає → ManiacSelectResponsePacket → onManiacTypeSelected()
 *  4. Запускаємо гру з вибраним типом
 *
 *  Таймаут: 60 секунд — 1 хвилина — якщо гравець не вибрав, вибирається RANDOM.
 */
public class PendingGameStarter {

    private static volatile PendingStart pending = null;

    public static void setPending(
            List<ServerPlayer>  allPlayers,
            ServerPlayer        maniac,
            List<java.net.InetAddress> ignore,  // not used
            java.util.List<net.minecraft.core.BlockPos> generators,
            java.util.List<net.minecraft.core.BlockPos> exits,
            java.util.List<ItemSpawnZone>               zones,
            java.util.List<EscapeZone>                  escZones,
            ManiacSpawnZone                             maniacSpawn,
            java.util.function.Consumer<ManiacType>     onReady) {

        pending = new PendingStart(allPlayers, maniac, generators, exits, zones, escZones, maniacSpawn, onReady);

        // Надіслати пакет відкриття меню маньяку
        ModMessages.sendOpenSelectToPlayer(maniac);

        // Повідомити інших гравців
        allPlayers.stream()
            .filter(p -> !p.getUUID().equals(maniac.getUUID()))
            .forEach(p -> p.sendSystemMessage(Component.translatable("maniacmod.game.choosing", maniac.getName().getString())));

        maniac.sendSystemMessage(Component.translatable("maniacmod.screen.select.player_hint"));

        // Таймаут 60 секунд — 1 хвилина
        new Thread(() -> {
            try {
                Thread.sleep(60_000);
                if (pending != null && pending.maniacUUID.equals(maniac.getUUID())) {
                    // Гравець не вибрав — використати RANDOM
                    maniac.getServer().execute(() -> {
                        if (pending != null) {
                            ManiacType random = ManiacType.values()[
                                new java.util.Random().nextInt(ManiacType.values().length)];
                            maniac.sendSystemMessage(Component.translatable("maniacmod.game.timeout", random.displayName));
                            onManiacTypeSelected(maniac, random);
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ManiacSelectTimeout").start();
    }

    public static void onManiacTypeSelected(ServerPlayer maniac, ManiacType chosen) {
        PendingStart snap = pending;
        if (snap == null) return;
        if (!snap.maniacUUID.equals(maniac.getUUID())) return;
        pending = null;

// Сховати waiting overlay для всіх
        if (maniac.getServer() != null) {
            ModMessages.hideSelectWaiting(maniac);
        }

        snap.onReady.accept(chosen);
    }

    public static void cancel() { pending = null; }

    // ── Inner ─────────────────────────────────────────────────────────────────

    private static class PendingStart {
        final UUID maniacUUID;
        final java.util.function.Consumer<ManiacType> onReady;

        PendingStart(List<ServerPlayer> all, ServerPlayer maniac,
                     java.util.List<net.minecraft.core.BlockPos> gens,
                     java.util.List<net.minecraft.core.BlockPos> exits,
                     java.util.List<ItemSpawnZone> zones,
                     java.util.List<EscapeZone> esc,
                     ManiacSpawnZone mSpawn,
                     java.util.function.Consumer<ManiacType> onReady) {
            this.maniacUUID = maniac.getUUID();
            this.onReady    = onReady;
        }
    }
}
