package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
 * Кріплення електричного дроту — «стопець» (ТЗ 2.3). Одна сутність на
 * КОЖЕН кінець дроту: їх завжди дві на одну пастку, і кожна створюється
 * {@link ElectricWireEntity} при розміщенні.
 *
 * ── Орієнтація ────────────────────────────────────────────────────
 * Модель 3×3×4 (за ТЗ) — витягнута, тож мусить дивитись УЗДОВЖ дроту.
 * Кут рахує сервер при створенні й клацає його в синхронізоване
 * поле {@link #YAW}; клієнт лише читає й повертає модель
 * (див. {@code ElectricWireMountRenderer}). Це той самий підхід, що
 * орієнтація генератора: стан — на сервері, рендер — на клієнті.
 *
 * ── Життєвий цикл ─────────────────────────────────────────────────
 * Кріплення не має власної ігрової логіки й не тримає стану матчу.
 * Воно існує рівно доки існує його дріт: {@link ElectricWireEntity}
 * видаляє обидва кріплення разом із собою. Якщо дріт зник іншим
 * шляхом (напр. чанк вивантажився саме на момент матчу) — «сирота»
 * прибирається у {@link #tick()}. Аварійне очищення всього матчу —
 * {@code MatchRuntimeRegistry.cleanup}.
 */
public class ElectricWireMountEntity extends Entity implements GeoEntity {

    /**
     * Кут повороту моделі навколо вертикалі, ГРАДУСИ: уздовж дроту, від
     * цього кріплення до іншого. Точний кут, а не одна з 4 сторін —
     * інакше на діагональному дроті кріплення дивилися б «криво».
     * 0° = модель дивиться на ПІВНІЧ (-Z), далі за годинниковою стрілкою.
     */
    private static final EntityDataAccessor<Float> YAW =
        SynchedEntityData.defineId(ElectricWireMountEntity.class, EntityDataSerializers.FLOAT);

    private static final String CONTROLLER_NAME = "electricWireMountController";
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    private static final String OWNER_WIRE_TAG = "WireId";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** UUID дроту-власника. Лише сервер; за ним кріплення знаходить, чи живий дріт. */
    private UUID ownerWireId;

    public ElectricWireMountEntity(EntityType<? extends ElectricWireMountEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Створює кріплення на точці {@code pos} (центр верхньої грані блока)
     * з орієнтацією {@code facing} уздовж дроту.
     */
    public static ElectricWireMountEntity spawn(EntityType<ElectricWireMountEntity> type, Level level,
                                                double x, double y, double z,
                                                float yawDegrees, UUID ownerWireId) {
        ElectricWireMountEntity mount = new ElectricWireMountEntity(type, level);
        mount.ownerWireId = ownerWireId;
        mount.moveTo(x, y, z, 0f, 0f);
        mount.entityData.set(YAW, yawDegrees);
        MatchRuntimeRegistry.register(mount);
        if (!level.isClientSide && !level.addFreshEntity(mount)) {
            return null;
        }
        return mount;
    }

    /** Кут повороту моделі, градуси (0° = північ). */
    public float yawDegrees() {
        return this.entityData.get(YAW);
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(YAW, 0f);
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (ownerWireId == null) {
            discard();
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) return;
        // Сирота: дріт зник (звільнення, кінець матчу, вивантаження чанка з
        // дротом) — кріплення не має лишатись стирчати саме по собі.
        if (!(serverLevel.getEntities().get(ownerWireId) instanceof ElectricWireEntity)) {
            discard();
        }
    }

    // ── Невразливість і приціл ───────────────────────────────────────────

    @Override
    public boolean isPickable() {
        return false;   // цілимось у ДРІТ, кріплення — лише декорація
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_NAME, 0,
            state -> state.setAndContinue(ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Yaw")) {
            this.entityData.set(YAW, tag.getFloat("Yaw"));
        } else if (tag.contains("Facing")) {
            // Старий формат (Direction 3D-index): (2D - 2) * 90°.
            this.entityData.set(YAW,
                (Direction.from3DDataValue(tag.getByte("Facing")).get2DDataValue() - 2) * 90f);
        }
        if (tag.hasUUID(OWNER_WIRE_TAG)) this.ownerWireId = tag.getUUID(OWNER_WIRE_TAG);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Yaw", this.entityData.get(YAW));
        if (ownerWireId != null) tag.putUUID(OWNER_WIRE_TAG, ownerWireId);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
