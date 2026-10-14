package com.log_to_kot.maniacmod.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
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

import java.util.UUID;

/**
 * Гачок сцени перемоги маніяка: модель гачка з «опудалом» виживого.
 *
 * <h3>Що це і чим НЕ є</h3>
 * Це НЕ ігровий {@code HookEntity} (той — механіка «повісити живого гравця», живе в матчі) і НЕ
 * гравець: реального гравця на гачку немає. У моделі гачка є кістки {@code player*} — тіло, голова,
 * руки, ноги; скін жертви накладається ТІЛЬКИ на них (див. {@code client/victory/VictoryHookRenderer}),
 * решта моделі малюється власною текстурою гачка. Той самий принцип, що в Snipers
 * ({@code DeathCorpse}: скін гравця на geo-моделі), але там скін лягає на всю модель, а тут — лише
 * на кістку гравця.
 *
 * <h3>Скін</h3>
 * Синхронізуються {@code UUID} жертви й, якщо відомі, властивість {@code textures} профілю та її
 * підпис: гравець міг вийти, і тоді таб-лист клієнта його вже не знає (так само, як для тіла
 * офлайн-гравця — {@code OfflineAvatarBase}). Клієнт спершу шукає скін у таб-листі, потім будує
 * профіль із {@code textures}, інакше показує дефолтний скін за UUID.
 *
 * <h3>Життя</h3>
 * Сутність тимчасова: її створює {@link VictorySceneModule}, реєструє у {@link MatchRuntimeRegistry}
 * (прибирання на RESET), а сам модуль знищує по завершенню сцени. Якщо сутність ожила з чанка без
 * модуля — вона сирота й сама себе видаляє.
 */
public class VictoryHookEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Float> HEADING =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.FLOAT);
    /** 0..2 — який із трьох варіантів погойдування грати (щоб тіла не гойдались синхронно). */
    private static final EntityDataAccessor<Integer> VARIANT =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> OCCUPIED =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> SKIN_UUID =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_TEXTURE =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE =
        SynchedEntityData.defineId(VictoryHookEntity.class, EntityDataSerializers.STRING);

    public static final int VARIANTS = 3;

    private static final RawAnimation[] SWAY = {
        RawAnimation.begin().thenLoop("sway_a"),
        RawAnimation.begin().thenLoop("sway_b"),
        RawAnimation.begin().thenLoop("sway_c")
    };
    private static final RawAnimation EMPTY = RawAnimation.begin().thenLoop("empty");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VictoryHookEntity(EntityType<? extends VictoryHookEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Створює гачок у світі. Усі дані виставляються ДО {@code addFreshEntity}, щоб потрапити в
     * перший пакет появи (інакше клієнт мигнув би порожнім гачком).
     *
     * @param victim  UUID жертви (скін); {@code null} — порожній гачок
     * @param texture значення властивості {@code textures} профілю або порожньо
     */
    public static VictoryHookEntity spawn(EntityType<VictoryHookEntity> type, Level level,
                                          double x, double y, double z, float headingYaw,
                                          UUID victim, String texture, String signature, int variant) {
        VictoryHookEntity entity = new VictoryHookEntity(type, level);
        float wrapped = headingYaw % 360f;
        entity.entityData.set(HEADING, wrapped < 0f ? wrapped + 360f : wrapped);
        entity.entityData.set(VARIANT, Math.floorMod(variant, VARIANTS));
        if (victim != null) {
            entity.entityData.set(OCCUPIED, true);
            entity.entityData.set(SKIN_UUID, victim.toString());
            entity.entityData.set(SKIN_TEXTURE, texture == null ? "" : texture);
            entity.entityData.set(SKIN_SIGNATURE, signature == null ? "" : signature);
        }
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[victory] гачок не додався у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return entity;
    }

    public float heading()          { return entityData.get(HEADING); }
    public int variant()            { return entityData.get(VARIANT); }
    public boolean isOccupied()     { return entityData.get(OCCUPIED); }
    public String skinTexture()     { return entityData.get(SKIN_TEXTURE); }
    public String skinSignature()   { return entityData.get(SKIN_SIGNATURE); }

    /** UUID жертви; {@code null}, якщо гачок порожній. */
    public UUID skinUuid() {
        String raw = entityData.get(SKIN_UUID);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEADING, 0f);
        entityData.define(VARIANT, 0);
        entityData.define(OCCUPIED, false);
        entityData.define(SKIN_UUID, "");
        entityData.define(SKIN_TEXTURE, "");
        entityData.define(SKIN_SIGNATURE, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.victory().owns(this)) discard();   // сирота
    }

    @Override public boolean isPickable()                         { return false; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    /** Сцена дивиться здалеку: малюємо, поки сутність узагалі в зоні відстеження. */
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return d < 256.0 * 256.0; }

    /** Модель вища й ширша за хітбокс (стійка з тілом) — інакше кадр відсікався б, коли хітбокс поза екраном. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(getX() - 3, getY() - 0.5, getZ() - 3, getX() + 3, getY() + 5.0, getZ() + 3);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "victoryHook", 0,
            state -> state.setAndContinue(isOccupied() ? SWAY[Math.floorMod(variant(), VARIANTS)] : EMPTY)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }

    /** Не зберігається: сцена тимчасова. */
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
