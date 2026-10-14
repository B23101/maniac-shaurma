package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.core.BlockPos;
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

/**
 * Приймач (радіо) — сутність, що з'являється на карті ОДИН раз за матч, у точці
 * {@code SpawnPointKind.RECEIVER}, обраній {@code SpawnPlanner}.
 *
 * <h3>Поворот</h3>
 * Той самий контракт, що в {@link GeneratorEntity}/{@link GroundItemEntity}: поворот по X
 * ({@link #rotationX()}) задається один раз при спавні, по Y — завжди {@link #ROTATION_Y} = 0.
 * Різниця лише в джерелі X: у генератора він випадковий, а в приймача — це напрямок, куди
 * дивився адмін у момент {@code /maniac point add receiver} (yaw точки). Тож картобудівник
 * сам вирішує, лицем до чого стоїть приймач.
 *
 * <h3>Що тут НЕ живе</h3>
 * Уся логіка (меню, виклик вертольота, стани) — у {@code evac.EvacModule}. Сутність — тіло:
 * показує стан анімацією й передає ПКМ модулю. Стан для візуалу
 * ({@link #visualStage()}) модуль штовхає в {@link #setVisualStage(int)}, а не сутність
 * читає чужі поля, — ті самі межі, що в генератора.
 *
 * <h3>Візуальні стани</h3>
 * 0 — без живлення (генератори не готові), 1 — готовий до виклику (блимає),
 * 2 — вертоліт викликано (антена активна), 3 — евакуація завершена/йде (тиха).
 */
public class ReceiverEntity extends Entity implements GeoEntity {

    private static final EntityDataAccessor<Float> ROTATION_X =
        SynchedEntityData.defineId(ReceiverEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> VISUAL_STAGE =
        SynchedEntityData.defineId(ReceiverEntity.class, EntityDataSerializers.INT);

    /** Нахил по Y — завжди 0 (як у генератора й предмета на землі). */
    public static final float ROTATION_Y = 0f;

    private static final RawAnimation ANIM_OFF = RawAnimation.begin().thenLoop("off");
    private static final RawAnimation ANIM_READY = RawAnimation.begin().thenLoop("ready");
    private static final RawAnimation ANIM_CALLED = RawAnimation.begin().thenLoop("called");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ReceiverEntity(EntityType<? extends ReceiverEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Створює приймач у світі. {@code headingYaw} — yaw точки розмітки (куди дивився адмін). */
    public static ReceiverEntity spawn(EntityType<ReceiverEntity> type, Level level,
                                       double x, double y, double z, float headingYaw) {
        ReceiverEntity entity = new ReceiverEntity(type, level);
        entity.entityData.set(ROTATION_X, headingYaw % 360f);
        entity.moveTo(x, y, z, ROTATION_Y, 0f);
        MatchRuntimeRegistry.register(entity);
        // register() лише ставить NBT-маркер для очищення; у світ додає тільки addFreshEntity
        // (той самий урок, що з генераторами: без нього тіло існувало б лише в Java).
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[evac] приймач не додався у світ на ({}, {}, {})", x, y, z);
        }
        return entity;
    }

    public float rotationX()   { return this.entityData.get(ROTATION_X); }
    public int visualStage()   { return this.entityData.get(VISUAL_STAGE); }

    public void setVisualStage(int stage) {
        if (stage != visualStage()) this.entityData.set(VISUAL_STAGE, stage);
    }

    public BlockPos receiverPos() { return blockPosition(); }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(ROTATION_X, 0f);
        this.entityData.define(VISUAL_STAGE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.evac().ownsReceiverAt(blockPosition())) {
            // Сирота: залишок минулого матчу, що ожив разом із чанком.
            discard();
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResult.PASS;
        match.evac().onReceiverInteract(sp, this);
        return InteractionResult.CONSUME;
    }

    @Override public boolean isPickable()                         { return true; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double d)  { return d < 128 * 128; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "receiverController", 5,
            state -> state.setAndContinue(switch (visualStage()) {
                case 1 -> ANIM_READY;
                case 2, 3 -> ANIM_CALLED;
                default -> ANIM_OFF;
            })));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(ROTATION_X, tag.getFloat("RotationX"));
        this.entityData.set(VISUAL_STAGE, tag.getInt("VisualStage"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("RotationX", this.entityData.get(ROTATION_X));
        tag.putInt("VisualStage", this.entityData.get(VISUAL_STAGE));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
