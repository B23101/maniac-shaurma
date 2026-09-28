package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Клієнтське життя гулу генератора — заміна серверного лупа
 * ({@code GeneratorLoopSoundscape}) для самого fade по гучності. Той
 * серверний клас лишається (одноразовий {@code generator_start} і
 * звук заливу бензину нікуди не діли — див. {@link GeneratorLoopClientSound}
 * для повного пояснення, чому саме гул виведено на клієнт).
 *
 * ── Чому радіус і зона повної гучності — тут, а не на сервері ──────────
 * Раніше ці числа жили в {@code GeneratorLoopSoundscape} на сервері;
 * тепер fade рахується локально з живої позиції гравця щокадру, тож
 * і межі fade природно переїхали сюди разом з логікою.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeneratorLoopClientSoundHandler {

    private static final double LOOP_RADIUS_BLOCKS = 16.0;

    /** У межах цієї дистанції — завжди повна гучність, без жодного fade. */
    private static final double FULL_VOLUME_RADIUS_BLOCKS = 3.0;

    private static final double FADE_WIDTH_BLOCKS = LOOP_RADIUS_BLOCKS - FULL_VOLUME_RADIUS_BLOCKS;

    /** По генератору (uuid сутності) — активний клієнтський прогін звуку, якщо є. */
    private static final Map<UUID, GeneratorLoopClientSound> ACTIVE_SOUNDS = new HashMap<>();

    /** Останнім разом порахована дистанція гравця до генератора — читає {@link GeneratorLoopClientSound.GeneratorLoopVolumeSource}. */
    private static final Map<UUID, double[]> DISTANCE_SQR = new HashMap<>();

    private GeneratorLoopClientSoundHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stopAll(mc);
            return;
        }

        AABB searchBox = player.getBoundingBox().inflate(LOOP_RADIUS_BLOCKS + 4.0);
        Set<UUID> seen = new HashSet<>();

        for (GeneratorEntity generator : mc.level.getEntitiesOfClass(GeneratorEntity.class, searchBox)) {
            UUID id = generator.getUUID();
            seen.add(id);

            if (!generator.isActive()) {
                stopFor(mc, id);
                continue;
            }

            Vec3 origin = generator.position().add(0, generator.getBbHeight() * 0.5, 0);
            double distSqr = player.distanceToSqr(origin);

            if (distSqr > LOOP_RADIUS_BLOCKS * LOOP_RADIUS_BLOCKS) {
                stopFor(mc, id);
                continue;
            }

            DISTANCE_SQR.put(id, new double[] { distSqr });

            if (!ACTIVE_SOUNDS.containsKey(id)) {
                GeneratorLoopClientSound sound = new GeneratorLoopClientSound(
                    origin.x, origin.y, origin.z,
                    () -> volumeForDistanceSqr(DISTANCE_SQR.getOrDefault(id, new double[] { 0 })[0]));
                ACTIVE_SOUNDS.put(id, sound);
                mc.getSoundManager().play(sound);
            }
        }

        // Генератори, яких цього тіку не видно взагалі (вивантажений чанк,
        // сутність деспавнилась) — гасимо так само, як і "поза радіусом".
        for (UUID id : new HashSet<>(ACTIVE_SOUNDS.keySet())) {
            if (!seen.contains(id)) stopFor(mc, id);
        }
    }

    private static float volumeForDistanceSqr(double distSqr) {
        double distance = Math.sqrt(distSqr);
        if (distance <= FULL_VOLUME_RADIUS_BLOCKS) return 1.0f;
        double t = (distance - FULL_VOLUME_RADIUS_BLOCKS) / FADE_WIDTH_BLOCKS;
        return (float) Math.max(0.0, 1.0 - t);
    }

    private static void stopFor(Minecraft mc, UUID id) {
        GeneratorLoopClientSound sound = ACTIVE_SOUNDS.remove(id);
        DISTANCE_SQR.remove(id);
        if (sound != null) {
            sound.markStopped();
            mc.getSoundManager().stop(sound);
        }
    }

    private static void stopAll(Minecraft mc) {
        for (UUID id : new HashSet<>(ACTIVE_SOUNDS.keySet())) stopFor(mc, id);
    }
}
