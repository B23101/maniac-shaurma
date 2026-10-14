package com.log_to_kot.maniacmod.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.core.particles.ParticleTypes;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Гравець сцени перемоги ГРАВЦІВ: geo-модель персонажа (взята зі snipers_shaurma,
 * {@code airplane_character}) зі скіном реального гравця.
 *
 * <ul>
 *   <li><b>Живий</b> (втік) — стоїть на точці {@code VICTORY_PLAYER} і танцює (одна з анімацій
 *       {@code dance*}).</li>
 *   <li><b>Привид</b> (не вижив) — стоїть на точці {@code VICTORY_GHOST}, висить у повітрі
 *       (анімація {@code ghost*} піднімає й гойдає модель), скін напівпрозорий із блакитним
 *       відтінком — це робить {@code VictoryPlayerRenderer}; тут лише прапор {@link #isGhost()} і
 *       частинки душ.</li>
 * </ul>
 * Сутність тимчасова: створює {@link VictorySceneModule}, він же й знищує; сирота (рестарт) сама
 * себе видаляє. Скін синхронізується як у {@link VictoryHookEntity}: UUID + {@code textures}.
 */
public class VictoryPlayerEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Float> HEADING =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> VARIANT =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> GHOST =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> SKIN_UUID =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_TEXTURE =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE =
        SynchedEntityData.defineId(VictoryPlayerEntity.class, EntityDataSerializers.STRING);

    private static final Map<String, RawAnimation> ANIMS = new HashMap<>();

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VictoryPlayerEntity(EntityType<? extends VictoryPlayerEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * @param ghost   {@code true} — привид (не вижив)
     * @param variant номер анімації (по колу з тих, що є в json) — щоб не танцювали синхронно
     */
    public static VictoryPlayerEntity spawn(EntityType<VictoryPlayerEntity> type, Level level,
                                            double x, double y, double z, float headingYaw,
                                            boolean ghost, int variant,
                                            UUID player, String texture, String signature) {
        VictoryPlayerEntity e = new VictoryPlayerEntity(type, level);
        float wrapped = headingYaw % 360f;
        e.entityData.set(HEADING, wrapped < 0f ? wrapped + 360f : wrapped);
        e.entityData.set(VARIANT, Math.max(0, variant));
        e.entityData.set(GHOST, ghost);
        if (player != null) {
            e.entityData.set(SKIN_UUID, player.toString());
            e.entityData.set(SKIN_TEXTURE, texture == null ? "" : texture);
            e.entityData.set(SKIN_SIGNATURE, signature == null ? "" : signature);
        }
        e.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(e);
        if (!level.isClientSide && !level.addFreshEntity(e)) {
            ManiacMod.LOGGER.warn("[victory] гравець сцени не додався у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return e;
    }

    public float heading()        { return entityData.get(HEADING); }
    public int variant()          { return entityData.get(VARIANT); }
    public boolean isGhost()      { return entityData.get(GHOST); }
    public String skinTexture()   { return entityData.get(SKIN_TEXTURE); }
    public String skinSignature() { return entityData.get(SKIN_SIGNATURE); }

    public UUID skinUuid() {
        String raw = entityData.get(SKIN_UUID);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEADING, 0f);
        entityData.define(VARIANT, 0);
        entityData.define(GHOST, false);
        entityData.define(SKIN_UUID, "");
        entityData.define(SKIN_TEXTURE, "");
        entityData.define(SKIN_SIGNATURE, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Привид «курить» синьою душею: легкі частинки навколо тіла, що висить у повітрі.
            if (isGhost() && tickCount % 4 == 0) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = 0.25 + random.nextDouble() * 0.3;
                level().addParticle(ParticleTypes.SOUL,
                    getX() + Math.cos(a) * r, getY() + 1.0 + random.nextDouble() * 1.4, getZ() + Math.sin(a) * r,
                    0.0, 0.02, 0.0);
            }
            return;
        }
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.victory().owns(this)) discard();   // сирота
    }

    @Override public boolean isPickable()                         { return false; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return d < 256.0 * 256.0; }

    /** Привид піднятий над землею — кадр не має відсікатись, коли хітбокс поза екраном. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(getX() - 2, getY() - 0.5, getZ() - 2, getX() + 2, getY() + 4.0, getZ() + 2);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "victoryPlayer", 0, state -> {
            String name = VictoryPlayerAnimations.pick(isGhost(), variant());
            return state.setAndContinue(ANIMS.computeIfAbsent(name, n -> RawAnimation.begin().thenLoop(n)));
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
