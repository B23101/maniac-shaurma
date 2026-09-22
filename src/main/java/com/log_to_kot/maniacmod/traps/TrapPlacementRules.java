package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Спільні правила розміщення пасток: не під гравцем і не ближче N
 * блоків до жодного гравця. Ці правила ОДНАКОВІ для капкана, мотузки,
 * дроту, розтяжки — тому це окремий клас, а не метод, дубльований у
 * кожному {@link TrapArchetype}-підкласі. Зміна правила (наприклад,
 * збільшити мінімальну відстань) — одна правка тут.
 *
 * ── Що тут НЕ перевіряється ──────────────────────────────────────────
 * Форма підлоги/стелі — це властивість КОНКРЕТНОЇ пастки
 * ({@link TrapArchetype#validatePlacement}). Тут лише те, що залежить
 * від людей навколо.
 *
 * ── Чому виживі беруться з матчу, а не з усіх гравців сервера ────────
 * «Не ближче N блоків до гравця» стосується тих, хто може потрапити в
 * пастку: виживих матчу. Маньяк, що ставить пастку впритул до себе, —
 * нормальна ситуація, а глядачі й ті, хто вже вибув, пастку не
 * спрацьовують і місце не блокують.
 */
public final class TrapPlacementRules {

    private TrapPlacementRules() {}

    /**
     * true, якщо позицію можна використати для розміщення БУДЬ-ЯКОЇ пастки.
     *
     * @param floor блок, НА який ставиться пастка
     */
    public static boolean canPlaceAt(ServerLevel level, BlockPos floor, ServerPlayer placer) {
        return check(level, floor, placer) == TrapArchetype.PlacementResult.OK;
    }

    /** Те саме, але з причиною відмови — для червоного квадрата й повідомлення. */
    public static TrapArchetype.PlacementResult check(ServerLevel level, BlockPos floor,
                                                       ServerPlayer placer) {
        if (!isWithinReach(floor, placer)) {
            return TrapArchetype.PlacementResult.TOO_FAR;
        }
        if (isTooCloseToAnySurvivor(floor, placer)) {
            return TrapArchetype.PlacementResult.TOO_CLOSE_TO_PLAYER;
        }
        return TrapArchetype.PlacementResult.OK;
    }

    /**
     * Чи цей блок у межах дальності розміщення від очей маньяка. Сервер
     * міряє це сам: позиція, яку надіслав клієнт, — лише «куди він
     * дивиться», і модифікований клієнт міг би вказати блок за півкарти.
     */
    public static boolean isWithinReach(BlockPos floor, ServerPlayer placer) {
        double range = ManiacConfigs.get(ConfigSchema.TRAP_PLACE_RANGE_BLOCKS);
        // Запас +1: рейкаст клієнта потрапляє в блок будь-якою точкою
        // грані, а ми міряємо до його центру — без запасу крайні
        // влучання відхилялись би без причини.
        double limit = range + 1.0;
        Vec3 center = Vec3.atCenterOf(floor);
        return placer.getEyePosition().distanceToSqr(center) <= limit * limit;
    }

    /**
     * Чи хтось із виживих стоїть ближче за {@code trapMinDistanceToPlayerBlocks}
     * до місця пастки. Це включає «не під гравцем»: гравець прямо над
     * блоком має відстань менше за будь-який осмислений мінімум, тож
     * окремої перевірки «під ногами» не потрібно — дві перевірки, що
     * означають одне, розійшлись би при першій зміні конфігу.
     *
     * Відстань міряється ГОРИЗОНТАЛЬНО від центру пастки: вертикальна
     * різниця не має значення, коли гравець висить на драбині чи стоїть
     * на поверх вище — пастка однаково опиниться під ним.
     */
    private static boolean isTooCloseToAnySurvivor(BlockPos floor, ServerPlayer placer) {
        int min = ManiacConfigs.get(ConfigSchema.TRAP_MIN_DISTANCE_TO_PLAYER);
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return false;

        double minSqr = (double) min * min;
        double cx = floor.getX() + 0.5;
        double cz = floor.getZ() + 0.5;

        for (UUID id : match.survivorIds()) {
            ServerPlayer survivor = match.onlinePlayer(id);
            if (survivor == null || survivor == placer) continue;

            double dx = survivor.getX() - cx;
            double dz = survivor.getZ() - cz;
            if (dx * dx + dz * dz < minSqr) return true;
        }
        return false;
    }

    /**
     * «Повноцінний блок»: суцільний куб 1×1×1 із рівною верхньою гранню.
     * Приймає {@link BlockGetter}, бо потрібна й клієнту (квадрат-підказка).
     * Слаби, килими, сходи, паркани, сніг, трава й повітря — НЕ повноцінні:
     * пастка на них стояла б у повітрі або тонула б у моделі.
     */
    public static boolean isFullBlock(BlockGetter level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.isCollisionShapeFullBlock(level, pos)
            && state.isFaceSturdy(level, pos, Direction.UP);
    }
}
