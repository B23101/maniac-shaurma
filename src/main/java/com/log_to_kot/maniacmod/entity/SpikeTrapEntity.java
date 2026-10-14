package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.traps.TrapModule;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * Шипи — «тіло» пастки у світі.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Як і {@link SmokeMineEntity}, сутність — суто ТІЛО: показує себе й питає
 * модуль у свій тік «хтось наступив?». Шкода, стаміна, підсвітка, пакети
 * ефектів — у {@link TrapModule#tickSpikeTrap}.
 *
 * ── Три стани (один синхронізований прапорець) ───────────────────────
 *   {@link #ARMED}      — чекає: плита з короткими вістрями, що ледь
 *                         виглядають із отворів (анімація {@code idle});
 *   {@link #EXTENDED}   — спрацювала: шипи ВИСКАКУЮТЬ із підлоги й стирчать
 *                         закривавлені ({@code spike_out}), доки не мине
 *                         {@code spikeTrapLingerTicks};
 *   {@link #RETRACTING} — шипи ховаються ({@code spike_in}) і сутність зникає.
 * Пастка ОДНОРАЗОВА: після спрацювання вдруге не б'є.
 *
 * ── Чому не ціль для лома ────────────────────────────────────────────
 * За дизайном шипи не можна знешкодити до спрацювання: {@link #isPickable()}
 * завжди {@code false}, тож лом по них навіть не цілиться (на відміну від
 * капкана й димової міни).
 */
public class SpikeTrapEntity extends Entity implements GeoEntity {

    public static final int ARMED = 0;
    public static final int EXTENDED = 1;
    public static final int RETRACTING = 2;

    /** Скільки тіків триває анімація «шипи ховаються» перед зникненням. */
    public static final int RETRACT_TICKS = 10;

    private static final EntityDataAccessor<Integer> STATE =
        SynchedEntityData.defineId(SpikeTrapEntity.class, EntityDataSerializers.INT);

    private static final String OWNER_TAG = "TrapOwner";

    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_OUT  = RawAnimation.begin().thenPlayAndHold("spike_out");
    private static final RawAnimation ANIM_IN   = RawAnimation.begin().thenPlayAndHold("spike_in");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** Хто поставив. Лише сервер; для статистики. */
    private UUID owner;

    /** Скільки тіків лишилось у поточному стані (EXTENDED / RETRACTING). Лише сервер. */
    private int phaseTicks;

    public SpikeTrapEntity(EntityType<? extends SpikeTrapEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Створює й додає шипи у світ. Викликається лише з
     * {@code SpikeTrapArchetype.spawn}.
     *
     * @param x,y,z центр верхньої грані блока-підлоги
     */
    public static SpikeTrapEntity spawn(EntityType<SpikeTrapEntity> type, Level level,
                                        double x, double y, double z, UUID owner) {
        SpikeTrapEntity entity = new SpikeTrapEntity(type, level);
        entity.owner = owner;
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);

        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[trap] шипи не додались у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return entity;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(STATE, ARMED);
    }

    public int state() {
        return this.entityData.get(STATE);
    }

    public boolean isArmed() {
        return state() == ARMED;
    }

    /** Лише сервер: пастка спрацювала — шипи вискакують і тримаються {@code lingerTicks}. */
    public void extend(int lingerTicks) {
        this.phaseTicks = Math.max(1, lingerTicks);
        this.entityData.set(STATE, EXTENDED);
    }

    /**
     * Лише сервер, раз на тік після спрацювання: веде таймер стану.
     *
     * @return {@code true}, коли шипи сховались і сутність пора прибрати
     */
    public boolean advanceAfterTrigger() {
        if (state() == ARMED) return false;
        if (--phaseTicks > 0) return false;
        if (state() == EXTENDED) {
            this.phaseTicks = RETRACT_TICKS;
            this.entityData.set(STATE, RETRACTING);
            return false;
        }
        return true;
    }

    public UUID owner() {
        return owner;
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        TrapModule traps = match.traps();
        if (!traps.isRegistered(this)) {
            // Сирота: сутність пережила матч (збережена з чанком).
            discard();
            return;
        }
        traps.tickSpikeTrap(this);
    }

    // ── Прицільність ─────────────────────────────────────────────────────

    /** Шипи не знешкодити ломом ні до, ні після спрацювання — у ціль не потрапляють. */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Невразлива до будь-якої шкоди. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "spikes", 0, animState -> {
            int s = state();
            if (s == EXTENDED) return animState.setAndContinue(ANIM_OUT);
            if (s == RETRACTING) return animState.setAndContinue(ANIM_IN);
            return animState.setAndContinue(ANIM_IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація (не переживає матч — лише вигляд) ───────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(STATE, tag.getInt("SpikeState"));
        if (tag.hasUUID(OWNER_TAG)) this.owner = tag.getUUID(OWNER_TAG);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("SpikeState", this.entityData.get(STATE));
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
