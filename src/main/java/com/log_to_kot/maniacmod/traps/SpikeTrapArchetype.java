package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.entity.SpikeTrapEntity;
import com.log_to_kot.maniacmod.offline.ManiacOffline;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.forge.stamina.StaminaService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;

import java.util.UUID;

/**
 * Шипи — одноразова пастка «боляче й видно».
 *
 * ── Що вона робить ───────────────────────────────────────────────────
 * Хто наступив — отримує {@code spikeTrapDamage} (за замовчуванням −10 хп),
 * миттєво втрачає всю стаміну (і ще {@code spikeTrapStaminaLockTicks} вона не
 * відновлюється) та світиться контуром для всіх {@code spikeTrapGlowTicks}.
 * Екран жертви показує «потрапив на шипи» ({@code client.SpikeHitEffect}).
 *
 * ── Як вибратися ─────────────────────────────────────────────────────
 * Ніяк і не треба: шипи жертву НЕ тримають — лок руху не ставиться,
 * міні-гри визволення немає (на відміну від капкана). Це лише удар.
 *
 * ── Де можна поставити ───────────────────────────────────────────────
 * Будь-який вільний блок: повноцінна підлога з вільним місцем над нею.
 *
 * ── Знешкодження ─────────────────────────────────────────────────────
 * До спрацювання знешкодити НЕ можна — ні ломом, ні чимось іншим
 * ({@link SpikeTrapEntity#isPickable()} = false). Після спрацювання шипи
 * постоять {@code spikeTrapLingerTicks} і зникнуть.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Тут лише відмінності цієї пастки: де стоїть, що спавниться і що робить
 * сам удар ({@link #applyEffect}). Тригер, підсвітку, звуки й пакети веде
 * {@link TrapModule#tickSpikeTrap}.
 */
public class SpikeTrapArchetype extends TrapArchetype {

    public static final String ID = "spike_trap";

    public SpikeTrapArchetype() {
        super(ID, "Шипи");
    }

    @Override
    public PlacementShape placementShape() {
        return PlacementShape.SINGLE_BLOCK;
    }

    /**
     * Один повноцінний блок-підлога й вільно над ним. Воду й лаву відсіюємо.
     * Перевіряється і на клієнті (колір квадрата), і на сервері — тому лише
     * {@link BlockGetter}, див. докстрінг {@link TrapArchetype#validatePlacement}.
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
        return SpikeTrapEntity.spawn(ModEntityTypes.SPIKE_TRAP.get(), level,
            floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5, placer.getUUID());
    }

    /**
     * Сам удар: шкода + обнулення стаміни. Підсвітку, «хвіст» стаміни й
     * клієнтські ефекти додає {@link TrapModule} — вони про стан, а не про
     * одну дозу.
     */
    @Override
    public void applyEffect(LivingEntity victim, BlockPos trapPos) {
        var match = ManiacMod.match();
        if (match == null) return;
        UUID id = ManiacOffline.idOf(victim);
        if (id == null) return;

        int damage = ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_DAMAGE);
        if (damage > 0 && match.damageSurvivor(id, damage)) {
            match.survivors().onSurvivorDowned(victim);
        }
        if (victim instanceof ServerPlayer sp) drainStamina(sp);
    }

    /** Стаміна — в нуль. Окремо, бо {@link TrapModule} повторює це щотіка протягом «хвоста». */
    public static void drainStamina(ServerPlayer victim) {
        if (StaminaService.isEnabled() && StaminaService.getStamina(victim) > 0) {
            StaminaService.setStamina(victim, 0, false);
        }
    }

    /** Спрацьовує лише на живого виживого на ногах (як капкан і міна). */
    @Override
    public boolean shouldTrigger(LivingEntity candidate) {
        var match = ManiacMod.match();
        if (match == null) return false;
        UUID id = ManiacOffline.idOf(candidate);
        if (id == null || !match.isSurvivor(id)) return false;
        if (candidate instanceof ServerPlayer sp && (sp.isSpectator() || sp.isCreative())) return false;
        SurvivorState st = match.survivorStateOf(id);
        return st != SurvivorState.UNCONSCIOUS && st != SurvivorState.CRAWLING;
    }

    @Override
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_PLACE_COOLDOWN_TICKS);
    }
}
