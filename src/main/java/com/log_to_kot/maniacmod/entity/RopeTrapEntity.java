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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * Мотузка-підвіс — «тіло» пастки у світі.
 *
 * ── Два життєвих стани ──────────────────────────────────────────────
 * {@code SNAPPED=false} — на підлозі лежить geo-модель мотузки (єдине, що
 * дозволяє виявити, що хтось наступив: хітбокс 0.9×0.3, як у капкана).
 * {@code SNAPPED=true} — жертва висить: geo-модель ховається, натомість
 * рендерер малює об'ємну нитку від стелі до ніг жертви.
 *
 * ── Що синхронізується (клієнту потрібно все, щоб намалювати) ───────
 *   • {@code CEILING_Y}   — Y точки кріплення на стелі (над сутністю);
 *   • {@code VICTIM}      — id сутності жертви (-1 — нікого);
 *   • {@code SWING}       — кут розгойдування, рад (0 = висить прямо);
 *   • {@code AXIS_YAW}    — yaw жертви в момент підвішування: задає вісь
 *                           гойдання (A/D = вліво/вправо від цього погляду);
 *   • {@code BREAK_ANGLE} — кут розриву, рад (для смужки в міні-грі).
 * Ігровий стан (симуляція, хто жертва) — лише в {@link TrapModule}.
 */
public class RopeTrapEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Boolean> SNAPPED =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> CEILING_Y =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> VICTIM =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SWING =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AXIS_YAW =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BREAK_ANGLE =
        SynchedEntityData.defineId(RopeTrapEntity.class, EntityDataSerializers.FLOAT);

    private static final String OWNER_TAG = "TrapOwner";
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private UUID owner;

    /** Для плавної інтерполяції кута між тіками (обидві сторони). */
    private float swingPrev, swingNow;

    public RopeTrapEntity(EntityType<? extends RopeTrapEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * @param x,y,z     центр верхньої грані блока-підлоги
     * @param ceilingY  Y нижньої грані стелі над пасткою
     */
    public static RopeTrapEntity spawn(EntityType<RopeTrapEntity> type, Level level,
                                       double x, double y, double z, double ceilingY, UUID owner) {
        RopeTrapEntity e = new RopeTrapEntity(type, level);
        e.owner = owner;
        e.entityData.set(CEILING_Y, (float) ceilingY);
        e.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(e);
        if (!level.isClientSide && !level.addFreshEntity(e)) {
            ManiacMod.LOGGER.warn("[trap] мотузка не додалась у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return e;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(SNAPPED, false);
        this.entityData.define(CEILING_Y, 0f);
        this.entityData.define(VICTIM, -1);
        this.entityData.define(SWING, 0f);
        this.entityData.define(AXIS_YAW, 0f);
        this.entityData.define(BREAK_ANGLE, 0.8f);
    }

    public boolean isSnapped()            { return entityData.get(SNAPPED); }
    public void setSnapped(boolean v)     { entityData.set(SNAPPED, v); }
    public double ceilingY()              { return entityData.get(CEILING_Y); }
    public int victimId()                 { return entityData.get(VICTIM); }
    public void setVictim(int id)         { entityData.set(VICTIM, id); }
    public float axisYaw()                { return entityData.get(AXIS_YAW); }
    public void setAxisYaw(float yaw)     { entityData.set(AXIS_YAW, yaw); }
    public float breakAngle()             { return entityData.get(BREAK_ANGLE); }
    public void setBreakAngle(float rad)  { entityData.set(BREAK_ANGLE, rad); }
    public void setSwing(float rad)       { entityData.set(SWING, rad); }
    public UUID owner()                   { return owner; }

    /** Кут розгойдування з інтерполяцією між тіками. */
    public float swing(float partial) {
        return Mth.lerp(partial, swingPrev, swingNow);
    }

    /** Точка кріплення нитки на стелі. */
    public Vec3 ceilingPoint() {
        return new Vec3(getX(), ceilingY(), getZ());
    }

    /** Одиничний горизонтальний вектор осі гойдання: «вправо» від yaw жертви. */
    public Vec3 swingAxis() {
        double rad = Math.toRadians(axisYaw());
        return new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        swingPrev = swingNow;
        swingNow = entityData.get(SWING);
        if (level().isClientSide) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        TrapModule traps = match.traps();
        if (!traps.isRegistered(this)) {
            discard();   // сирота: пережила матч
            return;
        }
        traps.tickRope(this);
    }

    // ── Прицільність і бокси ─────────────────────────────────────────────

    /** Нитку малюють по всій висоті й на радіус гойдання — не відсікати, поки центр поза кадром. */
    @Override
    public AABB getBoundingBoxForCulling() {
        double top = Math.max(ceilingY(), getY() + 1.0);
        return new AABB(getX() - 5.0, getY() - 0.5, getZ() - 5.0, getX() + 5.0, top + 0.3, getZ() + 5.0);
    }

    /** Приціл не ловить мотузку: ножиці цілять у ЖЕРТВУ, а не в сутність. */
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public InteractionResult interact(Player player, InteractionHand hand) { return InteractionResult.PASS; }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "ropeTrapController", 0,
            state -> state.setAndContinue(ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація (не переживає матч — лише вигляд) ───────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(CEILING_Y, tag.getFloat("CeilingY"));
        if (tag.hasUUID(OWNER_TAG)) owner = tag.getUUID(OWNER_TAG);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("CeilingY", (float) ceilingY());
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
