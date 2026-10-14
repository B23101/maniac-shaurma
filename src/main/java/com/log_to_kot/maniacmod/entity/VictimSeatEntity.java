package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.hooks.HookStatus;
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
import net.minecraftforge.network.NetworkHooks;

/**
 * Невидиме «сидіння» одного виживого, якого несуть на плечі або який висить на гаку.
 *
 * <h3>Навіщо сутність, а не телепорт гравця</h3>
 * Той самий прийом, що {@link ClimbCarrierEntity}: рух гравця рахує клієнт, а серверний
 * {@code teleportTo} щотіка дає ривки й змагається з клієнтським передбаченням. Пасажир же їде
 * за транспортом рівно: сервер ставить позицію сидіння, клієнт інтерполює її кадр у кадр, а
 * жертва просто «сидить» усередині. Це і є причина, чому жертва стоїть на гаку рівно, а не
 * «косо криво»: її позицію визначає одне число-формула в {@code HookModule}, а не фізика.
 *
 * <h3>Куди дивиться жертва</h3>
 * Ванільні пасажири не повертаються разом із транспортом, а тіло гравця дивиться туди, куди він
 * дивиться камерою. Тому сервер кладе потрібний yaw тіла в {@link #bodyYaw()} (синхронізоване
 * поле), а клієнт (пакет {@code client.hooks.PerchedPoseClient}) щотіка виставляє його тілу
 * пасажира. Камера самої жертви лишається вільною — вона може озиратись, тіло при цьому не крутиться.
 *
 * <h3>Чому на плечі жертву малює КЛІЄНТ, а не це сидіння</h3>
 * Сидіння рухається з частотою тіків сервера (20 Гц) і з мережевою затримкою, а маньяк на екрані
 * плавний (кадр у кадр) — тому жертва «відставала» й смикалась, виглядаючи як телепорт. Тепер для
 * {@code CARRIED} клієнт щокадру ставить модель жертви від інтерпольованого тіла маньяка
 * ({@code client.hooks.CarryPose}); сидіння лишається серверною «правдою» (позиція, хітбокс), а
 * {@link #carrierId()} каже клієнту, до кого прив'язуватись.
 *
 * <h3>Режим</h3>
 * {@link #status()}: {@code CARRIED} — на плечі, {@code HOOKED} — на гаку. Клієнт за ним вирішує,
 * яку позу грати.
 *
 * <h3>Що вимкнено</h3>
 * Як і в носія лазіння: гравець не «сидить» ({@link #shouldRiderSit()} = false), немає фізики,
 * гравітації, хітбокса для прицілу. Зісканути Shift-ом не вийде: {@code HookModule} щотіка
 * повертає того, хто зліз сам.
 */
public class VictimSeatEntity extends Entity {

    private static final EntityDataAccessor<Float> BODY_YAW =
        SynchedEntityData.defineId(VictimSeatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> STATUS =
        SynchedEntityData.defineId(VictimSeatEntity.class, EntityDataSerializers.INT);
    /** Id сутності-носія (маньяка) для CARRIED; -1 — нема (гак). Потрібен клієнту, щоб прив'язати жертву до носія в кадрі. */
    private static final EntityDataAccessor<Integer> CARRIER_ID =
        SynchedEntityData.defineId(VictimSeatEntity.class, EntityDataSerializers.INT);

    public VictimSeatEntity(EntityType<? extends VictimSeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static VictimSeatEntity spawn(EntityType<VictimSeatEntity> type, Level level,
                                         double x, double y, double z) {
        VictimSeatEntity entity = new VictimSeatEntity(type, level);
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) return null;
        return entity;
    }

    public float bodyYaw() { return this.entityData.get(BODY_YAW); }

    public void setBodyYaw(float yaw) {
        if (yaw != bodyYaw()) this.entityData.set(BODY_YAW, yaw);
    }

    public HookStatus status() { return HookStatus.fromCode(this.entityData.get(STATUS)); }

    public void setStatus(HookStatus status) {
        if (status.code() != this.entityData.get(STATUS)) this.entityData.set(STATUS, status.code());
    }

    /** Id маньяка, що несе жертву (див. {@link #CARRIER_ID}); -1 — ніхто. */
    public int carrierId() { return this.entityData.get(CARRIER_ID); }

    public void setCarrierId(int id) {
        if (id != carrierId()) this.entityData.set(CARRIER_ID, id);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(BODY_YAW, 0f);
        this.entityData.define(STATUS, HookStatus.NONE.code());
        this.entityData.define(CARRIER_ID, -1);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.hooks().ownsSeat(this)) discard();
    }

    /**
     * Ноги пасажира = Y сидіння. Ванільно ноги пасажира-гравця стоять на
     * {@code y + getPassengersRidingOffset() + player.getMyRidingOffset()}, де друге доданок для
     * гравця = -0.35, тож тут +0.35 — щоб вони скасувались (так само, як у ClimbCarrierEntity).
     */
    @Override
    public double getPassengersRidingOffset() { return 0.35; }

    @Override public boolean shouldRiderSit()                     { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengers().isEmpty(); }
    @Override public boolean isPickable()                         { return false; }
    @Override public boolean isPushable()                         { return false; }
    @Override public boolean hurt(DamageSource source, float amt) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource s)     { return true; }
    @Override public boolean isNoGravity()                        { return true; }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
