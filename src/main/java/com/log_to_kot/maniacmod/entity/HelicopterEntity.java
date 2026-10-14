package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
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

/**
 * Евакуаційний вертоліт. Чисте «тіло»: рухом керує ТІЛЬКИ {@code EvacModule}
 * (політ на крейсерській висоті → спуск → зависання → підйом → відліт), сутність сама
 * нічого не вирішує й не має ні фізики, ні гравітації, ні ШІ.
 *
 * <p>Не береться під приціл (не pickable): мотузка — окрема сутність
 * ({@link EvacRopeEntity}), тож випадковий клік по корпусу не запускає лазіння й не
 * заважає цілитись у мотузку.</p>
 *
 * <p>Єдина анімація — {@code fly}: гвинт, що крутиться завжди (вертоліт без обертів у
 * повітрі виглядав би зламаним, а «зупинений» стан тут не потрібен).</p>
 */
public class HelicopterEntity extends Entity implements GeoEntity {

    private static final RawAnimation ANIM_FLY = RawAnimation.begin().thenLoop("fly");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public HelicopterEntity(EntityType<? extends HelicopterEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static HelicopterEntity spawn(EntityType<HelicopterEntity> type, Level level,
                                         double x, double y, double z, float yaw) {
        HelicopterEntity entity = new HelicopterEntity(type, level);
        entity.moveTo(x, y, z, yaw, 0f);
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[evac] вертоліт не додався у світ на ({}, {}, {})", x, y, z);
        }
        return entity;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.evac().ownsHeli(this)) discard();
    }

    /**
     * Евакуація — сутність одного матчу, на диск не пишеться: інакше вертоліт, що опинився в
     * чанку, який вигрузили посеред польоту, лишився б там сиротою в збереженні світу.
     */
    @Override public boolean shouldBeSaved()                      { return false; }
    @Override public boolean isPickable()                         { return false; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    /** Видно здалеку: сам вертоліт — частина сигналу для маньяка. */
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return true; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "heliController", 0,
            state -> state.setAndContinue(ANIM_FLY)));
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
