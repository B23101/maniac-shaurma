package com.log_to_kot.maniacmod.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Маніяк у сцені перемоги: справжня geo-модель того маніяка, що грав цей раунд, але БЕЗ гравця —
 * просто сутність, що стоїть у позі й грає анімацію спокою.
 *
 * <p>У грі маніяк — це {@code Player} із підміненим рендером ({@code ManiacGeoRenderer}); у сцені
 * гравець-маніяк теж у камері-спостерігачі, тож для кіно потрібна окрема сутність. Вигляд береться
 * з тих самих {@code ManiacArchetype#visuals()}, тож новий маніяк потрапляє в сцену без жодного коду.
 * Життя й прибирання — як у {@link VictoryHookEntity}.</p>
 */
public class VictoryManiacEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Float> HEADING =
        SynchedEntityData.defineId(VictoryManiacEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> ARCHETYPE =
        SynchedEntityData.defineId(VictoryManiacEntity.class, EntityDataSerializers.STRING);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private RawAnimation idleAnim;

    public VictoryManiacEntity(EntityType<? extends VictoryManiacEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static VictoryManiacEntity spawn(EntityType<VictoryManiacEntity> type, Level level,
                                            double x, double y, double z, float headingYaw,
                                            String archetypeId) {
        VictoryManiacEntity entity = new VictoryManiacEntity(type, level);
        float wrapped = headingYaw % 360f;
        entity.entityData.set(HEADING, wrapped < 0f ? wrapped + 360f : wrapped);
        entity.entityData.set(ARCHETYPE, archetypeId == null ? "" : archetypeId);
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[victory] маніяк не доданий у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return entity;
    }

    public float heading()        { return entityData.get(HEADING); }
    public String archetypeId()   { return entityData.get(ARCHETYPE); }

    /** Архетип за id; {@code null}, якщо id порожній або невідомий (клієнт тоді малює тестового). */
    public ManiacArchetype archetype() {
        String id = archetypeId();
        return id.isEmpty() || !ManiacRegistry.exists(id) ? null : ManiacRegistry.get(id);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEADING, 0f);
        entityData.define(ARCHETYPE, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.victory().owns(this)) discard();
    }

    @Override public boolean isPickable()                         { return false; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return d < 256.0 * 256.0; }

    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(getX() - 3, getY() - 0.5, getZ() - 3, getX() + 3, getY() + 4.5, getZ() + 3);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "victoryManiac", 0, state -> {
            // Той самий RawAnimation щокадру (новий щоразу перезапускав би анімацію).
            if (idleAnim == null) {
                ManiacArchetype archetype = archetype();
                String idle = archetype == null ? "idle" : archetype.visuals().animationSet().idle();
                idleAnim = RawAnimation.begin().thenLoop(idle);
            }
            return state.setAndContinue(idleAnim);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
