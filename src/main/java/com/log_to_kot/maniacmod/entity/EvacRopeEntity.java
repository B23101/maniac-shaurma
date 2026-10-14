package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/**
 * Мотузка, що звисає з вертольота.
 *
 * <h3>Геометрія</h3>
 * Ноги сутності (її Y) стоять на ЗЕМЛІ під точкою вертольота, а хітбокс росте вгору на
 * {@link #maxLength()} блоків — тобто покриває мотузку по всій довжині, і ПКМ працює на
 * будь-якій висоті, де гравець може її дістати. Малюється ж мотузка підвішеною до верху
 * ({@code EvacRopeRenderer}): {@link #visibleLength()} росте від 0 до {@link #maxLength()},
 * і виглядає це як «кинув мотузку вниз».
 *
 * <h3>Коли її можна чіпати</h3>
 * Лише коли розмотана ПОВНІСТЮ ({@link #isFullyDropped()}): до того {@link #isPickable()}
 * повертає false, бо ПКМ по мотузці, що ще не торкнулась землі, не має запускати лазіння.
 * Рішення «чи можна лізти зараз» усе одно приймає сервер ({@code EvacModule.onRopeInteract}),
 * тут лише зручність прицілу.
 */
public class EvacRopeEntity extends Entity {

    private static final EntityDataAccessor<Float> MAX_LENGTH =
        SynchedEntityData.defineId(EvacRopeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> VISIBLE_LENGTH =
        SynchedEntityData.defineId(EvacRopeEntity.class, EntityDataSerializers.FLOAT);

    /**
     * Нижній кінець мотузки висить НАД землею на стільки блоків — не лежить на ній і не
     * волочиться. Це лише візуал ({@code EvacRopeRenderer}); хітбокс і логіка лазіння
     * рахують від землі, тож до кінця мотузки все одно можна дотягнутись.
     */
    public static final float GROUND_CLEARANCE = 0.5f;

    /** Товщина хітбокса: ширше за візуальну мотузку, щоб у неї було легко влучити. */
    private static final float HITBOX_WIDTH = 0.9f;

    public EvacRopeEntity(EntityType<? extends EvacRopeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static EvacRopeEntity spawn(EntityType<EvacRopeEntity> type, Level level,
                                       double x, double groundY, double z, float maxLength) {
        EvacRopeEntity entity = new EvacRopeEntity(type, level);
        entity.entityData.set(MAX_LENGTH, maxLength);
        entity.moveTo(x, groundY, z, 0f, 0f);
        entity.refreshDimensions();
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[evac] мотузка не додалась у світ на ({}, {}, {})", x, groundY, z);
        }
        return entity;
    }

    public float maxLength()      { return this.entityData.get(MAX_LENGTH); }
    public float visibleLength()  { return this.entityData.get(VISIBLE_LENGTH); }

    /** Довжина видимої мотузки з урахуванням зазору над землею (0 … max − GROUND_CLEARANCE). */
    public float drawnLength() {
        float max = maxLength();
        return max <= GROUND_CLEARANCE ? 0f : visibleLength() * (max - GROUND_CLEARANCE) / max;
    }

    /** Розмотана до землі. */
    public boolean isFullyDropped() { return visibleLength() >= maxLength() - 0.01f; }

    /** Виставляє, скільки мотузки вже видно (0…max). Викликає лише {@code EvacModule}. */
    public void setVisibleLength(float length) {
        float clamped = Math.max(0f, Math.min(length, maxLength()));
        if (Math.abs(clamped - visibleLength()) > 0.001f) this.entityData.set(VISIBLE_LENGTH, clamped);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(MAX_LENGTH, 14f);
        this.entityData.define(VISIBLE_LENGTH, 0f);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(HITBOX_WIDTH, Math.max(0.5f, maxLength()));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (MAX_LENGTH.equals(key)) refreshDimensions();
        super.onSyncedDataUpdated(key);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.evac().ownsRope(this)) discard();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return isFullyDropped() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResult.PASS;
        match.evac().onRopeInteract(sp, this);
        return InteractionResult.CONSUME;
    }

    /**
     * Евакуація — сутність одного матчу, на диск не пишеться: інакше вертоліт, що опинився в
     * чанку, який вигрузили посеред польоту, лишився б там сиротою в збереженні світу.
     */
    @Override public boolean shouldBeSaved()                      { return false; }
    @Override public boolean isPickable()                         { return isFullyDropped(); }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return true; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(MAX_LENGTH, tag.getFloat("MaxLength"));
        this.entityData.set(VISIBLE_LENGTH, tag.getFloat("VisibleLength"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("MaxLength", maxLength());
        tag.putFloat("VisibleLength", visibleLength());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
