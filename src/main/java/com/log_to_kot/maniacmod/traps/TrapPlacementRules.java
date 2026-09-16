package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Спільні правила розміщення пасток (з презентації "Пастки —
 * Загальні правилами"): не можна під гравцем, не можна ближче
 * 3 блоків до гравця. Ці правила ОДНАКОВІ для капкана, мотузки,
 * дроту, розтяжки — тому це окремий клас, а не метод, дубльований
 * у кожному TrapArchetype-підкласі.
 *
 * TODO: перенести реальну перевірку відстані/позиції з v3
 * (де саме зараз ці правила закодовані — знайти при перенесенні
 * логіки з game/BearTrapState.java та сусідніх файлів).
 */
public final class TrapPlacementRules {

    private TrapPlacementRules() {}

    /** true якщо позицію можна використати для розміщення БУДЬ-ЯКОЇ пастки. */
    public static boolean canPlaceAt(BlockPos pos, ServerPlayer placer) {
        return !isUnderPlayer(pos, placer) && !isTooCloseToAnyPlayer(pos, placer);
    }

    private static boolean isUnderPlayer(BlockPos pos, ServerPlayer placer) {
        // TODO: перенести реальну перевірку "не під гравцем" з v3
        return false;
    }

    private static boolean isTooCloseToAnyPlayer(BlockPos pos, ServerPlayer placer) {
        // Радіус береться з конфігу (maniac.trapMinDistanceToPlayerBlocks),
        // а не з константи: це одне число для всіх типів пасток.
        int min = ManiacConfigs.get(ConfigSchema.TRAP_MIN_DISTANCE_TO_PLAYER);
        // TODO(міграція traps): пройти по виживих і порівняти відстань з min.
        return false;
    }
}
