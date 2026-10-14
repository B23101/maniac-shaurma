package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.registry.ModParticles;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.sounds.SoundEvent;
import com.log_to_kot.maniacmod.registry.ModSounds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

/**
 * Мотузка — одноразова пастка-підвіс.
 *
 * ── Де можна поставити ───────────────────────────────────────────────
 * Один повний блок-підлога, над яким є ВІЛЬНИЙ стовп заввишки від
 * {@code ropeTrapMinCeilingBlocks} (3) до {@code ropeTrapMaxCeilingBlocks}
 * (6) блоків, що впирається в суцільну стелю (грань, повернена вниз,
 * «міцна»). Нитка вішається на цю стелю рівно над пасткою.
 *
 * ── Що вона робить ───────────────────────────────────────────────────
 * Хто наступив — підвішується вгору ногами, не може рухатись, крутити
 * камерою й падати. Вибратись: ножиці (ПКМ по ньому, або він сам) чи
 * міні-гра розкачування A/D до розриву нитки. Уся логіка життя — у
 * {@link TrapModule} (tickRope/onRopeSwing/cutRope), тут лише «де ставити»
 * і «що спавниться» — так само, як у капкана.
 */
public class RopeBindArchetype extends TrapArchetype {

    public static final String ID = "rope_bind";

    public RopeBindArchetype() {
        super(ID, "Мотузка");
    }

    @Override
    public PlacementShape placementShape() {
        return PlacementShape.SINGLE_BLOCK;
    }

    /** Мотузка має свій звук розміщення, не капканний. */
    @Override
    public SoundEvent placeSound() {
        return ModSounds.ROPE_PLACE.get();
    }

    /**
     * Скільки вільних блоків над підлогою до першого перешкоджаючого,
     * але не більше {@code cap + 1} (щоб цикл був скінченним).
     *
     * @return кількість вільних блоків над {@code floor}
     */
    public static int freeHeight(BlockGetter level, BlockPos floor, int cap) {
        int free = 0;
        BlockPos p = floor.above();
        int limit = level.getMaxBuildHeight();
        while (free <= cap && p.getY() < limit) {
            var st = level.getBlockState(p);
            boolean open = st.getCollisionShape(level, p).isEmpty() && st.getFluidState().isEmpty();
            if (!open) break;
            free++;
            p = p.above();
        }
        return free;
    }

    /** Y нижньої грані стелі над підлогою (тільки для валідного місця). */
    public static double ceilingY(BlockGetter level, BlockPos floor) {
        int free = freeHeight(level, floor, ManiacConfigs.get(ConfigSchema.ROPE_TRAP_MAX_CEILING_BLOCKS));
        return floor.getY() + 1.0 + free;
    }

    /**
     * Перевіряється і на клієнті (колір квадрата), і на сервері — тому лише
     * {@link BlockGetter}: див. докстрінг {@link TrapArchetype#validatePlacement}.
     */
    @Override
    public PlacementResult validatePlacement(BlockGetter level, BlockPos floor) {
        if (!TrapPlacementRules.isFullBlock(level, floor)) {
            return PlacementResult.NOT_FULL_BLOCK;
        }
        int min = ManiacConfigs.get(ConfigSchema.ROPE_TRAP_MIN_CEILING_BLOCKS);
        int max = Math.max(min, ManiacConfigs.get(ConfigSchema.ROPE_TRAP_MAX_CEILING_BLOCKS));

        int free = freeHeight(level, floor, max);
        if (free > max) return PlacementResult.CEILING_TOO_HIGH;

        BlockPos ceilPos = floor.above(free + 1);
        if (ceilPos.getY() >= level.getMaxBuildHeight()) return PlacementResult.NO_CEILING;
        var ceil = level.getBlockState(ceilPos);
        if (!ceil.getFluidState().isEmpty() || !ceil.isFaceSturdy(level, ceilPos, Direction.DOWN)) {
            return PlacementResult.NO_CEILING;
        }
        if (free < min) return PlacementResult.CEILING_TOO_LOW;
        return PlacementResult.OK;
    }

    @Override
    public Entity spawn(ServerLevel level, BlockPos floor, ServerPlayer placer) {
        return RopeTrapEntity.spawn(ModEntityTypes.ROPE_TRAP.get(), level,
            floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5,
            ceilingY(level, floor), placer.getUUID());
    }

    /** Частинки спрацювання; сам підвіс веде {@link TrapModule}. */
    @Override
    public void applyEffect(LivingEntity victim, BlockPos trapPos) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            if (viewer.serverLevel() != level) continue;
            level.sendParticles(viewer, ModParticles.TRAP_SNAP.get(), true,
                victim.getX(), victim.getY() + 0.25, victim.getZ(), 5, 0.25, 0.08, 0.25, 0.02);
        }
    }

    @Override
    public boolean shouldTrigger(LivingEntity candidate) {
        var match = ManiacMod.match();
        if (match == null) return false;
        var id = com.log_to_kot.maniacmod.offline.ManiacOffline.idOf(candidate);
        if (id == null || !match.isSurvivor(id)) return false;
        if (candidate instanceof ServerPlayer sp && (sp.isSpectator() || sp.isCreative())) return false;
        SurvivorState st = match.survivorStateOf(id);
        return st != SurvivorState.UNCONSCIOUS && st != SurvivorState.CRAWLING;
    }

    @Override
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.ROPE_TRAP_PLACE_COOLDOWN_TICKS);
    }
}
