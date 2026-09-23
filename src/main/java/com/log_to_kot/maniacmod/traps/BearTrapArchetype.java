package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;

/**
 * Капкан — найпростіша пастка: один блок, ПКМ ставить, гравець, що
 * наступив, знерухомлюється, отримує трохи шкоди й може зламати ногу.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Тут лише те, що відрізняє капкан від інших пасток: ДЕ він стоїть
 * (повний блок, вільне місце над ним), ЩО спавниться (BearTrapEntity)
 * і яка шкода. Життєвий цикл (розміщення, тригер, звільнення ломом,
 * перезарядки) — в {@link TrapModule} і однаковий для всіх пасток.
 *
 * ── Числа — у конфігу ────────────────────────────────────────────────
 * Перезарядка розміщення (40 с), шкода й шкода ногам не зашиті в клас:
 * див. {@code ConfigSchema.BEAR_TRAP_*}.
 */
public class BearTrapArchetype extends TrapArchetype {

    public static final String ID = "bear_trap";

    public BearTrapArchetype() {
        super(ID, "Капкан");
    }

    @Override
    public PlacementShape placementShape() {
        return PlacementShape.SINGLE_BLOCK;
    }

    /**
     * Капкан стоїть на ПОВНОЦІННОМУ блоці, і над ним має бути вільно:
     * інакше сутність виявилась би всередині іншого блока. Заодно
     * відсіюємо воду й лаву — капкан не плаває.
     */
    @Override
    public PlacementResult validatePlacement(BlockGetter level, BlockPos floor) {
        if (!TrapPlacementRules.isFullBlock(level, floor)) {
            return PlacementResult.NOT_FULL_BLOCK;
        }
        BlockPos above = floor.above();
        var aboveState = level.getBlockState(above);
        if (!aboveState.getCollisionShape(level, above).isEmpty() || !aboveState.getFluidState().isEmpty()) {
            return PlacementResult.NO_ROOM_ABOVE;
        }
        return PlacementResult.OK;
    }

    @Override
    public Entity spawn(ServerLevel level, BlockPos floor, ServerPlayer placer) {
        return BearTrapEntity.spawn(ModEntityTypes.BEAR_TRAP.get(), level,
            floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5, placer.getUUID());
    }

    /**
     * Шкода, знерухомлення й удар по ногах виконує {@link TrapModule}
     * (він же знає про сесію жертви). Тут лише число, щоб модуль не
     * читав конфіг капкана в обхід архетипу.
     */
    @Override
    public void applyEffect(ServerPlayer victim, BlockPos trapPos) {
        var match = ManiacMod.match();
        if (match == null) return;

        spawnTriggerParticles(victim);

        int damage = ManiacConfigs.get(ConfigSchema.BEAR_TRAP_DAMAGE);
        if (damage > 0 && match.damageSurvivor(victim.getUUID(), damage)) {
            match.survivors().onSurvivorDowned(victim);
        }
        match.survivors().onTrapLegDamage(victim,
            ManiacConfigs.get(ConfigSchema.BEAR_TRAP_LEG_DAMAGE));
    }

    /**
     * Партиклі самого попадання (захлопування) — окремо від партиклів
     * перелому ноги в {@code SurvivorModule#spawnLegBreakParticles}:
     * ця подія стається щоразу, коли капкан спрацював, незалежно від
     * того, чи вистачило шкали цілісності ніг для реального перелому
     * цим конкретним ударом.
     */
    private static void spawnTriggerParticles(ServerPlayer victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            if (viewer.serverLevel() != level) continue;
            level.sendParticles(viewer, net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, true,
                victim.getX(), victim.getY() + 0.3, victim.getZ(),
                6, 0.25, 0.1, 0.25, 0.0);
        }
    }

    /** Спрацьовує лише на живого виживого, який ще не в пастці й не лежить. */
    @Override
    public boolean shouldTrigger(ServerPlayer candidate) {
        var match = ManiacMod.match();
        if (match == null) return false;
        var id = candidate.getUUID();
        if (!match.isSurvivor(id)) return false;
        if (candidate.isSpectator() || candidate.isCreative()) return false;
        return match.survivorStateOf(id) != com.log_to_kot.maniacmod.survivors.SurvivorState.UNCONSCIOUS
            && match.survivorStateOf(id) != com.log_to_kot.maniacmod.survivors.SurvivorState.CRAWLING;
    }

    @Override
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.BEAR_TRAP_PLACE_COOLDOWN_TICKS);
    }
}
