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

/**
 * Невидимий «ліфт» одного гравця, що лізе по мотузці.
 *
 * <h3>Навіщо сутність, а не телепорт чи швидкість гравця</h3>
 * Рух гравця рахує клієнт; серверний {@code teleportTo} щотіку дає ривки, а швидкість через
 * {@code setDeltaMovement} змагається з клієнтським передбаченням і з замком руху
 * shaurma-lib. Пасажир же їде за транспортом рівно — сервер задає позицію носія, клієнт
 * інтерполює його кадр у кадр, а гравець просто сидить усередині. Це той самий прийом, що
 * вагонетки й човни: нічого не треба «пробивати» крізь чужі системи.
 *
 * <h3>Що навмисно вимкнено</h3>
 * <ul>
 *   <li>{@link #shouldRiderSit()} = false — гравець НЕ сідає в позу «сидить у човні»,
 *       а висить у стійці (руки донизу; власна поза лазіння — окрема арт-задача);</li>
 *   <li>без фізики й гравітації, без хітбокса, що заважає прицілу (не pickable);</li>
 *   <li>удар маньяка по пасажиру працює як завжди (гравець лишається ціллю), але віддача
 *       пасажира не зрушує — він їде разом із носієм. Саме звідси правило дизайну:
 *       удар без нокауту лазіння не зупиняє.</li>
 * </ul>
 * Зісканути Shift-ом не вийде: {@code EvacModule} щотіку повертає гравця, що зліз сам.
 */
public class ClimbCarrierEntity extends Entity {

    public ClimbCarrierEntity(EntityType<? extends ClimbCarrierEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static ClimbCarrierEntity spawn(EntityType<ClimbCarrierEntity> type, Level level,
                                           double x, double y, double z) {
        ClimbCarrierEntity entity = new ClimbCarrierEntity(type, level);
        entity.moveTo(x, y, z, 0f, 0f);
        MatchRuntimeRegistry.register(entity);
        if (!level.isClientSide && !level.addFreshEntity(entity)) return null;
        return entity;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.evac().ownsCarrier(this)) discard();
    }

    /**
     * Ноги пасажира = Y носія. Ванільно ноги пасажира-гравця стоять на
     * {@code y + getPassengersRidingOffset() + player.getMyRidingOffset()}, де друге доданок
     * для гравця = -0.35, тож тут +0.35 — щоб вони скасувались.
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
