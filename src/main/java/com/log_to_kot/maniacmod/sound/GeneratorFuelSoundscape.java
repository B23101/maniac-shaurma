package com.log_to_kot.maniacmod.sound;

import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.registry.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Звук заливу бензину — той самий принцип персонального серверного
 * ресинку, що {@link GeneratorLoopSoundscape} (докладний коментар про
 * "чому звук не переставав бути чутним" — той самий баг і той самий
 * фікс: персональний {@code ClientboundStopSoundPacket} гравцю, який
 * вийшов з радіуса, а не лише коли залив узагалі припиняється).
 *
 * ── Чому інтервал перевідтворення бере НАЙКОРОТШИЙ з трьох варіантів ──
 * {@code fuel_fill} — одна подія sounds.json із трьома .ogg-файлами
 * (fuel_fill_1/2/3), клієнт сам випадково обирає один при кожному
 * пакеті. Сервер не знає, який саме вибере клієнт цього разу, тож
 * інтервал до наступної копії береться по НАЙКОРОТШОМУ з трьох файлів.
 *
 * ── Джерело "чи ще заливають" ──────────────────────────────────────────
 * {@link GeneratorEntity#stagePercent()} уже синхронізується щотік і
 * росте, поки БУДЬ-ХТО заливає в цю точку. Порівняння з попереднім
 * тіком тут, на сервері — так кожен новий глядач, що заходить у радіус
 * ПІД ЧАС заливу, отримує звук так само, як і той, хто був поруч від
 * самого початку.
 */
public final class GeneratorFuelSoundscape {

    private static final double FUEL_RADIUS_BLOCKS = 12.0;

    /**
     * Скільки тіків без приросту вважати кінцем заливу — гасить дрібні
     * паузи синхронізації. БАГФІКС: раніше жорстка {@code 3} — коротша
     * за реальну діру між прийнятими тіками при повільних темпах
     * заливу, через що звук стрибав вкл/викл посеред безперервного
     * заливу. Тепер бере реальний темп із
     * {@link com.log_to_kot.maniacmod.map.GeneratorModule#maxFuelTickGapTicks()}.
     */
    private static int graceTicks() {
        return com.log_to_kot.maniacmod.map.GeneratorModule.maxFuelTickGapTicks() + 1;
    }

    /** Найкоротший з трьох варіантів fuel_fill_1/2/3 — див. коментар класу вище. */
    private static final int REPLAY_INTERVAL_TICKS = Math.max(1, Math.min(
        OggSoundLength.ticksOf("fuel_fill_1"),
        Math.min(OggSoundLength.ticksOf("fuel_fill_2"), OggSoundLength.ticksOf("fuel_fill_3"))));

    private static final Map<UUID, Integer> LAST_PERCENT = new HashMap<>();
    private static final Map<UUID, Integer> GRACE_LEFT = new HashMap<>();

    /** За кожним генератором — гравці, кому підтверджено поточний прогін, і тік їхнього наступного повтору. */
    private static final Map<UUID, Map<UUID, Long>> NEXT_REPLAY_TICK = new HashMap<>();

    private GeneratorFuelSoundscape() {}

    public static void tick(GeneratorEntity generator) {
        if (!(generator.level() instanceof ServerLevel level)) return;
        UUID id = generator.getUUID();

        if (!generator.isFuelStage()) {
            stopAllAndForget(level, id);
            return;
        }

        int current = generator.stagePercent();
        int last = LAST_PERCENT.getOrDefault(id, current);
        boolean grew = current > last;
        LAST_PERCENT.put(id, current);

        int grace = GRACE_LEFT.getOrDefault(id, 0);
        boolean filling;
        if (grew) {
            grace = graceTicks();
            filling = true;
        } else {
            grace = grace - 1;
            filling = grace > 0;
        }
        GRACE_LEFT.put(id, Math.max(grace, 0));

        if (!filling) {
            stopAllAndForget(level, id);
            return;
        }

        long now = level.getGameTime();
        Map<UUID, Long> perPlayer = NEXT_REPLAY_TICK.computeIfAbsent(id, k -> new HashMap<>());
        Vec3 origin = generator.position().add(0, generator.getBbHeight() * 0.5, 0);

        Set<UUID> stillOnLevel = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            UUID playerId = player.getUUID();
            stillOnLevel.add(playerId);
            boolean inRange = player.distanceToSqr(origin.x, origin.y, origin.z)
                <= FUEL_RADIUS_BLOCKS * FUEL_RADIUS_BLOCKS;

            boolean wasConfirmed = perPlayer.containsKey(playerId);
            if (!inRange) {
                if (wasConfirmed) {
                    perPlayer.remove(playerId);
                    sendStop(level, playerId);
                }
                continue;
            }

            Long due = perPlayer.get(playerId);
            if (due == null || now >= due) {
                sendStart(level, player, origin);
                perPlayer.put(playerId, now + REPLAY_INTERVAL_TICKS);
            }
        }
        perPlayer.keySet().removeIf(uuid -> !stillOnLevel.contains(uuid));
    }

    private static void sendStart(ServerLevel level, ServerPlayer player, Vec3 origin) {
        Holder<SoundEvent> holder = ForgeRegistries.SOUND_EVENTS
            .getHolder(ModSounds.FUEL_FILL.get()).orElse(null);
        if (holder == null) return;

        player.connection.send(new ClientboundSoundPacket(
            holder, SoundSource.BLOCKS, origin.x, origin.y, origin.z,
            1.0f, 1.0f, level.random.nextLong()));
    }

    private static void sendStop(ServerLevel level, UUID playerId) {
        ResourceLocation soundId = ForgeRegistries.SOUND_EVENTS.getKey(ModSounds.FUEL_FILL.get());
        if (soundId == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return;
        player.connection.send(new ClientboundStopSoundPacket(soundId, SoundSource.BLOCKS));
    }

    private static void stopAllAndForget(ServerLevel level, UUID id) {
        Map<UUID, Long> confirmed = NEXT_REPLAY_TICK.remove(id);
        LAST_PERCENT.remove(id);
        GRACE_LEFT.remove(id);
        if (confirmed != null) {
            for (UUID uuid : confirmed.keySet()) sendStop(level, uuid);
        }
    }

    public static void forget(UUID generatorId) {
        NEXT_REPLAY_TICK.remove(generatorId);
        LAST_PERCENT.remove(generatorId);
        GRACE_LEFT.remove(generatorId);
    }
}
