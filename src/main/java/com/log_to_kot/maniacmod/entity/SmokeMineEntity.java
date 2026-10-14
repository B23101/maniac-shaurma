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
 * Димова міна — «тіло» пастки у світі.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Як і {@link BearTrapEntity}, сутність — суто ТІЛО: показує себе й
 * питає модуль у свій тік «хтось наступив?». Уся ігрова логіка (хмара,
 * сліпота, шкода, підсвітка, глушіння звуків) живе в {@link TrapModule}.
 *
 * ── Вигляд: помітна пастка ───────────────────────────────────────────
 * Geo-модель ({@code geo/entity/smoke_mine.geo.json}): темна пластина
 * з жовтим кантом-«знаком небезпеки», кришка й червоний світлодіод, що
 * блимає (анімація {@code idle}). Рендерер ({@code SmokeMineRenderer})
 * додає кільце-пульс, що розбігається по підлозі.
 *
 * ── Один синхронізований прапорець ───────────────────────────────────
 * {@code TRIGGERED=false} — міна чекає (пластина малюється, ціль для
 * лома); {@code TRIGGERED=true} — спрацювала: пластину ховаємо, у ціль
 * лома більше не потрапляємо, а сама сутність живе, поки тримається
 * хмара (її прибирає {@link TrapModule}).
 */
public class SmokeMineEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Boolean> TRIGGERED =
        SynchedEntityData.defineId(SmokeMineEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String OWNER_TAG = "TrapOwner";

    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** Хто поставив. Лише сервер; для статистики. */
    private UUID owner;

    public SmokeMineEntity(EntityType<? extends SmokeMineEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Створює й додає міну у світ. Викликається лише з
     * {@code SmokeMineArchetype.spawn}.
     *
     * @param x,y,z центр верхньої грані блока-підлоги
     */
    public static SmokeMineEntity spawn(EntityType<SmokeMineEntity> type, Level level,
                                        double x, double y, double z, UUID owner) {
        SmokeMineEntity entity = new SmokeMineEntity(type, level);
        entity.owner = owner;
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);

        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[trap] димова міна не додалась у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return entity;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(TRIGGERED, false);
    }

    public boolean isTriggered() {
        return this.entityData.get(TRIGGERED);
    }

    /** Лише сервер: викликає {@link TrapModule} при спрацюванні. */
    public void setTriggered(boolean triggered) {
        this.entityData.set(TRIGGERED, triggered);
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
        traps.tickSmokeMine(this);
    }

    // ── Прицільність ─────────────────────────────────────────────────────

    /** Ціль для лома — лише поки міна не спрацювала. */
    @Override
    public boolean isPickable() {
        return !isTriggered();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Невразлива до будь-якої шкоди: знешкодити можна лише ломом через TrapModule. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** ПКМ нічого не робить: лом б'є ЛКМ (як по капкану). */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    /** Мала й пласка — запас навколо хітбокса, щоб лому було в що цілитись. */
    @Override
    public float getPickRadius() {
        return 0.2f;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "mine", 0,
            state -> state.setAndContinue(ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація (не переживає матч — лише вигляд) ───────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(TRIGGERED, tag.getBoolean("Triggered"));
        if (tag.hasUUID(OWNER_TAG)) this.owner = tag.getUUID(OWNER_TAG);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Triggered", this.entityData.get(TRIGGERED));
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
