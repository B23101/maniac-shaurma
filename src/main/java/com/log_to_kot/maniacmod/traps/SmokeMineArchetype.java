package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.entity.SmokeMineEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import dev.shaurmalib.forge.stamina.StaminaService;

/**
 * Димова (газова) міна — одноразова пастка «заборони зони».
 *
 * ── Що вона робить ───────────────────────────────────────────────────
 * Хто наступив — випускає густу хмару газу (за замовчуванням квадрат 8×8
 * блоків, висота {@code smokeMineHeightBlocks}). Поки гравець у хмарі: сліпота,
 * стаміна на нулі, приглушені звуки, підсвітка контуром для всіх, плями диму
 * на екрані й шкода щосекунди. Після виходу стаміна ще
 * {@code smokeMineStaminaLockTicks} (10 с) не відновлюється.
 * Вибратись — наосліп вибігти за межі зони: міна нічого не тримає й
 * не блокує рух.
 *
 * ── Де можна поставити ───────────────────────────────────────────────
 * Один повний блок-підлога з вільним місцем над ним. Міну добре видно:
 * темна пластина з жовтогарячим кантом, блимаючий червоний
 * «світлодіод» і кільце-пульс ({@code SmokeMineRenderer}).
 *
 * ── Як знищити до спрацювання ────────────────────────────────────────
 * Ломом (ЛКМ), як капкан — див. {@code TrapModule#onCrowbarHit}.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Тут лише те, що відрізняє цю пастку: де стоїть, що спавниться й що
 * робить ОДНА доза газу з гравцем ({@link #applyEffect}). Хмару, її
 * зону, таймери, підсвітку й глушіння веде {@link TrapModule}.
 */
public class SmokeMineArchetype extends TrapArchetype {

    public static final String ID = "smoke_mine";

    public SmokeMineArchetype() {
        super(ID, "Димова міна");
    }

    @Override
    public PlacementShape placementShape() {
        return PlacementShape.SINGLE_BLOCK;
    }

    /**
     * Міна стоїть на ПОВНОЦІННОМУ блоці, і над ним має бути вільно: і
     * сама міна, і хмара над нею. Воду й лаву відсіюємо — газ не під водою.
     * Перевіряється і на клієнті (колір квадрата), і на сервері — тому
     * лише {@link BlockGetter}, див. докстрінг {@link TrapArchetype#validatePlacement}.
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
        return SmokeMineEntity.spawn(ModEntityTypes.SMOKE_MINE.get(), level,
            floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5, placer.getUUID());
    }

    /**
     * ОДНА доза газу: шкода + (поновлення) сліпоти. {@link TrapModule}
     * викликає це в момент, коли гравець потрапив у хмару, і далі щосекунди,
     * поки він у ній лишається — тож «−1 хп за кожну секунду перебування»
     * і «сліпота не спадає всередині хмари» випливають з одного виклику.
     *
     * <p>Підсвітку й глушіння звуків веде модуль (вони не про одну дозу, а
     * про стан «я в хмарі»).</p>
     */
    @Override
    public void applyEffect(LivingEntity victim, BlockPos trapPos) {
        var match = ManiacMod.match();
        if (match == null) return;

        int damage = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_DAMAGE_PER_SECOND);
        java.util.UUID id = com.log_to_kot.maniacmod.offline.ManiacOffline.idOf(victim);
        if (id == null) return;
        if (damage > 0 && match.damageSurvivor(id, damage)) {
            match.survivors().onSurvivorDowned(victim);
        }

        // Без іконки й частинок ефекту: сліпоту видно й так, а значок
        // у куті екрана — зайва підказка там, де гравець «нічого не бачить».
        victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
            ManiacConfigs.get(ConfigSchema.SMOKE_MINE_BLINDNESS_TICKS), 0, false, false, false));
    }

    /**
     * Те, що газ робить КОЖЕН тік, поки гравець у хмарі (викликає
     * {@link TrapModule#tickSmoke}): тримає сліпоту й гасить стаміну.
     *
     * <p>Окремо від {@link #applyEffect} (раз на секунду), бо:
     * сліпоту треба підтримувати без «мигання» (поновлюємо, щойно лишилось
     * менше секунди), а стаміна інакше відновлювалась би між дозами —
     * газ має ДУШИТИ, тож поки гравець у хмарі, стаміна лишається на нулі
     * й бігти він не може.</p>
     */
    public static void applyGasTick(ServerPlayer victim) {
        int blindTicks = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_BLINDNESS_TICKS);
        MobEffectInstance current = victim.getEffect(MobEffects.BLINDNESS);
        if (current == null || current.getDuration() < Math.min(blindTicks, 25)) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                blindTicks, 0, false, false, false));
        }

        drainStamina(victim);
    }

    /**
     * Притискає стаміну до нуля. Окремий метод, бо його викликають двічі:
     * щотіка, поки гравець у хмарі ({@link #applyGasTick}), і щотіка
     * протягом «хвоста» після виходу ({@code TrapModule#tickSmoke},
     * {@code smokeMineStaminaLockTicks}) — стаміна не відновлюється, поки
     * цей виклик триває.
     */
    public static void drainStamina(ServerPlayer victim) {
        if (StaminaService.isEnabled() && StaminaService.getStamina(victim) > 0) {
            StaminaService.setStamina(victim, 0, false);
        }
    }

    /** Спрацьовує лише на живого виживого на ногах (як капкан). */
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
        return ManiacConfigs.get(ConfigSchema.SMOKE_MINE_PLACE_COOLDOWN_TICKS);
    }
}
