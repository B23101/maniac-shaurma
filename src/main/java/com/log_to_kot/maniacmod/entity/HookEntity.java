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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Гак — на нього маньяк вішає виживого (механіка Dead by Daylight).
 *
 * <h3>Поворот</h3>
 * Той самий контракт, що в {@link ReceiverEntity}: «куди дивиться гак» задається один раз при
 * спавні ({@link #heading()} = yaw точки розмітки, тобто куди дивився адмін у момент
 * {@code /maniac point add hook}). Нахил завжди 0. Напрямок «вперед» гака — це напрямок
 * ванільного yaw: {@code (-sin, cos)}. Саме від нього рахується, куди дивиться підвішена
 * жертва ({@code hooks.hangYawDegrees}) і де вона висить ({@code hooks.hangForward/hangSide}).
 *
 * <h3>Що тут НЕ живе</h3>
 * Уся логіка — у {@code hooks.HookModule}. Сутність — тіло: показує, чи зайнятий гак
 * ({@link #isOccupied()}, штовхається модулем), і більше нічого не вирішує. ПКМ по гаку
 * обробляє серверна подія {@code EntityInteract} у {@code ServerHooks}, а не метод
 * {@link #interact}: маньяка треба обслужити й тоді, коли приціл потрапив у жертву на гаку,
 * а не в сам гак.
 *
 * <h3>Модель</h3>
 * Зараз — ТЕСТОВА ({@code geo/entity/hook.geo.json}): стовп і загнутий гак. Замінюється
 * заміною трьох файлів (geo/texture/animation) без змін у коді; положення жертви потім
 * підганяється числами в {@code hooks.yml}.
 */
public class HookEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Float> HEADING =
        SynchedEntityData.defineId(HookEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> OCCUPIED =
        SynchedEntityData.defineId(HookEntity.class, EntityDataSerializers.BOOLEAN);

    /** Нахил по Y — завжди 0 (як у генератора, приймача й предмета на землі). */
    public static final float ROTATION_Y = 0f;

    private static final RawAnimation ANIM_EMPTY = RawAnimation.begin().thenLoop("empty");
    private static final RawAnimation ANIM_OCCUPIED = RawAnimation.begin().thenLoop("occupied");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public HookEntity(EntityType<? extends HookEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Створює гак у світі. {@code headingYaw} — yaw точки розмітки (куди дивився адмін). */
    public static HookEntity spawn(EntityType<HookEntity> type, Level level,
                                   double x, double y, double z, float headingYaw) {
        HookEntity entity = new HookEntity(type, level);
        entity.entityData.set(HEADING, wrapYaw(headingYaw));
        entity.moveTo(x, y, z, ROTATION_Y, 0f);
        MatchRuntimeRegistry.register(entity);
        // register() лише ставить NBT-маркер для очищення; у світ додає тільки addFreshEntity.
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[hooks] гак не додався у світ на ({}, {}, {})", x, y, z);
        }
        return entity;
    }

    private static float wrapYaw(float yaw) {
        float wrapped = yaw % 360f;
        return wrapped < 0f ? wrapped + 360f : wrapped;
    }

    /** Куди дивиться гак: ванільний yaw у градусах, 0..360. */
    public float heading() { return this.entityData.get(HEADING); }

    public boolean isOccupied() { return this.entityData.get(OCCUPIED); }

    public void setOccupied(boolean occupied) {
        if (occupied != isOccupied()) this.entityData.set(OCCUPIED, occupied);
    }

    /** Одиничний вектор «вперед» гака в площині XZ. */
    public Vec3 forward() {
        double rad = Math.toRadians(heading());
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }

    /** Одиничний вектор «праворуч» (дивлячись у бік гака) в площині XZ. */
    public Vec3 right() {
        double rad = Math.toRadians(heading());
        return new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(HEADING, 0f);
        this.entityData.define(OCCUPIED, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.hooks().owns(this)) {
            // Сирота: залишок минулого матчу, що ожив разом із чанком.
            discard();
        }
    }

    /** Логіка ПКМ — у події EntityInteract (див. javadoc класу); тут лише не заважаємо. */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override public boolean isPickable()                         { return true; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    /** Контур для маньяка видно здалеку (див. HookHighlightGlow), тож і малювати треба далеко. */
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return d < 512.0 * 512.0; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "hookController", 5,
            state -> state.setAndContinue(isOccupied() ? ANIM_OCCUPIED : ANIM_EMPTY)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(HEADING, tag.getFloat("Heading"));
        this.entityData.set(OCCUPIED, false);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Heading", this.entityData.get(HEADING));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
