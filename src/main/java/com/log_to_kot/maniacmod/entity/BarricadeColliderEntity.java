package com.log_to_kot.maniacmod.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/**
 * Невидимий кубик-колайдер гнучкої барикади.
 *
 * <h3>Чому сутність, а не міксин на колізію</h3>
 * Дошки барикади — не блоки, а кастомний рендер, але пройти крізь них не можна. Ванільна колізія
 * гравця рахується і на клієнті (передбачення руху), і на сервері, і бере в розрахунок будь-яку
 * сутність із {@code canBeCollidedWith() == true} (так працюють човни). Це єдиний спосіб дати
 * колізію без міксина на приватний {@code Entity.collide}, і він автоматично синхронізується.
 * AABB осьовий, тому діагональна дошка апроксимується ланцюжком кубиків, що перекриваються
 * (параметри — {@code BarricadeGeometry}).
 *
 * <h3>Життєвий цикл</h3>
 * Колайдерами повністю володіє {@link BarricadeEntity}: створює їх на своєму першому тіку й
 * прибирає разом із собою. Самі вони НЕ зберігаються у чанк ({@link #shouldBeSaved()}) і не тікають —
 * тому після вивантаження чанка сирітських колайдерів не лишається, а власник створить їх знову.
 */
public class BarricadeColliderEntity extends Entity {

    public BarricadeColliderEntity(EntityType<? extends BarricadeColliderEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    @Override
    public void tick() {
        // Свідомо порожньо: нерухомий кубик нічого не рахує (їх десятки на барикаду).
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPickable() {
        // Приціл/удар не чіпає колайдер: дошки ловить власний промінь по справжній геометрії.
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isInvisible() {
        return true;
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
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
