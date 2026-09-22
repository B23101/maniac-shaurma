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
 * Капкан — «тіло» пастки у світі.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Як і {@link GeneratorEntity}, сутність — суто ТІЛО: показує модель й
 * анімацію та є ціллю для прицілу. Уся ігрова логіка (хто потрапив, хто
 * кого звільнив, знерухомлення, перезарядки) живе в
 * {@link TrapModule}. Тік сутності лише питає модуль «хтось наступив?».
 * Так капкан можна знищити й переспавнити без втрати стану, а решта
 * пасток (розтяжка, мотузка) отримає таку ж форму, а не власну.
 *
 * ── Два стани, один синхронізований прапорець ───────────────────────
 * {@code SNAPPED=false} — розкритий, чекає (анімація {@code idle_open});
 * {@code SNAPPED=true} — захлопнув жертву (анімація {@code idle_closed}).
 * Клієнт лише читає прапорець і не вгадує стан.
 *
 * ── Хітбокс і приціл ─────────────────────────────────────────────────
 * Хітбокс невисокий (див. {@code ModEntityTypes.BEAR_TRAP}), але
 * {@link #isPickable()} = true, тож приціл ловить його, і ЛКМ ломом
 * доходить до сервера як {@code AttackEntityEvent} (сама сутність
 * невразлива: {@link #hurt} завжди false, шкоди від удару немає).
 *
 * ── Чому НЕ Entity.interact ──────────────────────────────────────────
 * За дизайном лом б'є ЛКМ, а не ПКМ. ПКМ по капкану не робить нічого —
 * інакше виживий, що просто тисне ПКМ повз, «використовував» би його.
 */
public class BearTrapEntity extends Entity implements GeoEntity {

    /** true — захлопнув жертву. */
    private static final EntityDataAccessor<Boolean> SNAPPED =
        SynchedEntityData.defineId(BearTrapEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String CONTROLLER_NAME = "bearTrapController";

    /**
     * Дві анімації, обидві loop — той самий патерн, що GeneratorEntity
     * (idle/active). Момент «клацання» вбудований у початок
     * {@code idle_closed} самим художником (перші кадри — захлоп, далі
     * нерухома поза): так контролер лишається з одним setAndContinue і
     * не потребує стану «вже програв перехід», який на клієнті
     * розсинхронізувався б при перезаході в зону видимості.
     */
    private static final RawAnimation ANIM_OPEN   = RawAnimation.begin().thenLoop("idle_open");
    private static final RawAnimation ANIM_CLOSED = RawAnimation.begin().thenLoop("idle_closed");

    private static final String OWNER_TAG = "TrapOwner";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** Хто поставив. Лише сервер; для статистики й щоб маньяк не потрапляв у свій капкан. */
    private UUID owner;

    public BearTrapEntity(EntityType<? extends BearTrapEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Створює й додає капкан у світ. Викликається з
     * {@code BearTrapArchetype.spawn} — єдиного місця, де він
     * з'являється, тож правила створення не розповзаються по коду.
     *
     * @param x,y,z центр верхньої грані блока-підлоги
     */
    public static BearTrapEntity spawn(EntityType<BearTrapEntity> type, Level level,
                                       double x, double y, double z, UUID owner) {
        BearTrapEntity entity = new BearTrapEntity(type, level);
        entity.owner = owner;
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);

        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[trap] капкан не додався у світ на ({}, {}, {})", x, y, z);
            return null;
        }
        return entity;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(SNAPPED, false);
    }

    public boolean isSnapped() {
        return this.entityData.get(SNAPPED);
    }

    /** Лише сервер: викликає {@link TrapModule} при спрацюванні/звільненні. */
    public void setSnapped(boolean snapped) {
        this.entityData.set(SNAPPED, snapped);
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
            // Сирота: сутність пережила матч (збережена з чанком) —
            // те саме, що робить GeneratorEntity.syncFromPoi. Живий
            // капкан завжди зареєстрований у модулі ДО першого тіку.
            discard();
            return;
        }
        traps.tickTrap(this);
    }

    // ── Прицільність ─────────────────────────────────────────────────────

    @Override
    public boolean isPickable() {
        return true;
    }

    /** Гравці не виштовхують капкан. */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** Невразливий до будь-якої шкоди: ламати капкан можна лише ломом через TrapModule. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /** ПКМ нічого не робить (див. клас-докстрінг). */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    /**
     * Невеликий запас навколо хітбокса для прицілу: капкан низький,
     * і без запасу в нього важко цілитись ломом.
     */
    @Override
    public float getPickRadius() {
        return 0.2f;
    }

    /** Видно так далеко, як його відстежує сервер: маньяк має бачити свої пастки. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_NAME, 0,
            state -> state.setAndContinue(isSnapped() ? ANIM_CLOSED : ANIM_OPEN)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────
    //
    // Капкан не переживає матч: TrapModule знає про нього лише поки
    // матч живий, а сирота знищується в tick(). Зберігаємо тільки вигляд.

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(SNAPPED, tag.getBoolean("Snapped"));
        if (tag.hasUUID(OWNER_TAG)) this.owner = tag.getUUID(OWNER_TAG);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putBoolean("Snapped", this.entityData.get(SNAPPED));
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
