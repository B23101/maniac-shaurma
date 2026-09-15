package com.log_to_kot.maniacmod.cinematic;

import com.log_to_kot.maniacmod.network.ModMessages;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.sound.SoundHelper;
import com.log_to_kot.maniacmod.entity.ManiacType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import com.log_to_kot.maniacmod.config.ManiacConfig;
import java.util.List;
import java.util.function.Consumer;

/**
 * Кінематографічна вступна сценка.
 *
 * ВІДЕО + КАРТА:
 * ──────────────────────────────────────────────────────────────
 *  0.0s  Починається відео "intro.mp4" (відтворюється на повний екран)
 *        Відео має бути у: .minecraft/maniacmod/video/intro.mp4
 *        Паралельно: сліпота + заморожування гравців
 *
 *  [відео триває ~20 сек — монолог Ковальського]
 *
 * 20.0s  Відео завершується → показ карти зони (title/subtitle)
 * 20.0s  Звук сирени (game_start.ogg)
 * 20.0s  "[ КАРТА ЗОНИ ]" + маньяк розкритий
 * 25.0s  Екран очищається → гра починається
 * ──────────────────────────────────────────────────────────────
 *
 * FALLBACK (якщо FFmpeg не знайдено або відео відсутнє):
 *   Автоматично використовує title-сценку без відео.
 */
public class IntroScene {

    public static void play(List<ServerPlayer> players,
                             ServerPlayer maniacPlayer,
                             ManiacType maniacType,
                             Runnable onComplete) {
        // Якщо сценка вимкнена в конфігу — одразу запускаємо гру
        if (!ManiacConfig.isIntroSceneEnabled()) {
            onComplete.run();
            return;
        }

        new Thread(() -> {
            try {
                runScene(players, maniacPlayer, maniacType, onComplete);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onComplete.run();
            }
        }, "ManiacIntroScene").start();
    }

    private static void runScene(List<ServerPlayer> players,
                                  ServerPlayer maniacPlayer,
                                  ManiacType maniacType,
                                  Runnable onComplete) throws InterruptedException {

        // ── Заморозити гравців ────────────────────────────────────────────────
        toAll(players, p -> {
            p.connection.send(new ClientboundClearTitlesPacket(true));
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,         700, 0, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 700, 10, false, false));
        });

        // ── Запустити відео на всіх клієнтах ─────────────────────────────────
        // Відео: .minecraft/maniacmod/video/intro.mp4
        // Якщо FFmpeg не знайдено — VideoPlayer сам покаже повідомлення
        ModMessages.sendVideoToAll("intro", 24);

        // ── Чекаємо поки відео грає (~20 сек) ────────────────────────────────
        // Тривалість відео = тривалість запису Ковальського
        // Змінюй INTRO_DURATION_MS під свій запис
        final long INTRO_DURATION_MS = 20_000;
        Thread.sleep(INTRO_DURATION_MS);

        // ── Зупинити відео ────────────────────────────────────────────────────
        ModMessages.stopVideoForAll();

        // ── Показ карти зони + сирена ─────────────────────────────────────────
        String icon = switch (maniacType) {
            case CHUCKY     -> "🪆";
            case SLENDERMAN -> "👤";
        };
        String desc = switch (maniacType) {
            case CHUCKY     ->
                "§c" + maniacType.displayName + "  §8|  §7Висота §c0.5 бл  §8|  §7Проходить крізь щілини";
            case SLENDERMAN ->
                "§9" + maniacType.displayName + "  §8|  §7Висота §94.0 бл  §8|  §7Блокується стелями < 4 бл";
        };

        // Сирена
        toAll(players, p -> {
            SoundHelper.playTo(p, ModSounds.GAME_START.get(), 1.0f, 1.0f);
            p.removeEffect(MobEffects.BLINDNESS); // Зняти сліпоту — тепер видно карту
        });

        // Заголовок "КАРТА ЗОНИ"
        toAll(players, p -> {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
            p.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("§c§l[ КАРТА ЗОНИ ]")));
        });

        Thread.sleep(600);

        // Розкриття маньяка
        toAll(players, p -> {
            if (p.getUUID().equals(maniacPlayer.getUUID())) {
                p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(
                        "§4§l☠  ТИ — МАНЬЯК  §8[ " + icon + " §e" + maniacType.displayName + " §8]\n"
                        + "§c§lПОЛЮВАННЯ ПОЧИНАЄТЬСЯ!")));
            } else {
                p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(
                        "§7Загроза: §c" + maniacPlayer.getName().getString()
                        + "  §8[ " + icon + "  " + desc + " §8]\n"
                        + "§a⚠ Знайди предмети  §8·  §aГенератори  §8·  §aВтікай!")));
            }
        });

        Thread.sleep(4500);

        // ── Кінець — очистити та стартувати ──────────────────────────────────
        toAll(players, p -> {
            p.connection.send(new ClientboundClearTitlesPacket(true));
            p.removeEffect(MobEffects.BLINDNESS);
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        });
        Thread.sleep(300);

        onComplete.run();
    }

    private static void toAll(List<ServerPlayer> players, Consumer<ServerPlayer> action) {
        players.forEach(action);
    }
}
