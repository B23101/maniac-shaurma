package com.log_to_kot.maniacmod.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Жест «піднімає предмет із землі». Кліп {@code pick_up_item.json} більше
 * НЕ грає через PAL (шар не спрацьовував) — кути рук відтворює
 * {@code MixinGeneratorWorkThirdPerson} з {@link HandPoseTracks}, як і в
 * ліхтарика. Тут лише облік «коли хто почав».
 *
 * Час рахуємо в ТІКАХ клієнта + partialTick у міксині, тож жест плавний і
 * однаковий у всіх глядачів. Пакет несе лише «хто почав» — кінця немає:
 * жест сам закінчується за {@link #DURATION_TICKS}.
 */
@OnlyIn(Dist.CLIENT)
public final class ItemPickupPose {

    /** 0.9167 с × 20. */
    public static final int DURATION_TICKS = 19;

    /** UUID → tick клієнта, на якому жест почався. */
    private static final Map<UUID, Long> STARTED = new HashMap<>();

    private ItemPickupPose() {}

    /** Лишено для сумісності з {@code ClientSetup} — реєструвати більше нічого. */
    public static void registerAction() {}

    /** З {@code ItemPickupPacket}: цей гравець щойно почав піднімати предмет. */
    public static void start(UUID playerId) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        STARTED.put(playerId, level.getGameTime());
    }

    /**
     * Скільки СЕКУНД минуло від старту жесту або {@code -1}, якщо жесту
     * немає (не починався або вже скінчився).
     */
    public static float elapsedSeconds(UUID playerId, float partialTick) {
        Long start = STARTED.get(playerId);
        if (start == null) return -1f;
        var level = Minecraft.getInstance().level;
        if (level == null) return -1f;
        float ticks = (level.getGameTime() - start) + partialTick;
        if (ticks < 0f) ticks = 0f;
        if (ticks > DURATION_TICKS) {
            STARTED.remove(playerId);
            return -1f;
        }
        return ticks / 20f;
    }

    public static void reset() {
        STARTED.clear();
    }
}
