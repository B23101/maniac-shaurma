package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Предмет, що лежить на карті.
 *
 * Це не ванільний {@code ItemEntity}: лут має власну модель з окремою
 * анімацією, лежить нерухомо й не підстрибує.
 *
 * ── Поворот ──────────────────────────────────────────────────────────
 * Поворот по X — випадковий, по Y — завжди 0. Обидва задаються ОДИН
 * раз, поки предмет ще падає, і після приземлення не змінюються:
 * предмет має лежати так, як упав, а не крутитися на місці.
 *
 * Випадковий X — це те, що не дає двом однаковим предметам поруч
 * виглядати копіями. Нульовий Y — те, що не дає предмету встромитися
 * в поверхню боком.
 *
 * ── Падіння ──────────────────────────────────────────────────────────
 * Сутність падає (гравітація увімкнена), доки не торкнеться землі.
 * Після приземлення фізика вимикається назавжди — сотня лежачих
 * предметів на карті 300×300 не має щотіку рахувати рух того, що не
 * рухається.
 *
 * {@code loot.fallMaxTicks} — запобіжник: якщо предмет упав у
 * порожнечу й не приземлився, він фіксується примусово, а не падає
 * вічно.
 */
public class GroundItemEntity extends Entity {

    private static final EntityDataAccessor<String> ITEM_ID =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.STRING);

    /** Поворот по X. Випадковий, задається при спавні. */
    private static final EntityDataAccessor<Float> ROTATION_X =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.FLOAT);

    /** true, коли предмет уже приземлився і більше не рухається. */
    private static final EntityDataAccessor<Boolean> LANDED =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.BOOLEAN);

    /** Поворот по Y. Завжди 0 — константа, не синхронізується. */
    public static final float ROTATION_Y = 0f;

    private int fallTicks = 0;

    public GroundItemEntity(EntityType<? extends GroundItemEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Створює предмет, який зараз почне падати.
     *
     * @param rotationX випадковий поворот, отриманий із
     *                  {@code loot.GroundItemPlacement}
     */
    public static GroundItemEntity dropping(EntityType<GroundItemEntity> type, Level level,
                                            Item item, double x, double y, double z,
                                            float rotationX) {
        GroundItemEntity entity = new GroundItemEntity(type, level);
        entity.setItem(item);
        entity.entityData.set(ROTATION_X, rotationX % 360f);
        entity.moveTo(x, y, z, ROTATION_Y, 0f);
        MatchRuntimeRegistry.register(entity);
        return entity;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(ITEM_ID, "");
        this.entityData.define(ROTATION_X, 0f);
        this.entityData.define(LANDED, false);
    }

    public void setItem(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        this.entityData.set(ITEM_ID, key == null ? "" : key.toString());
    }

    /** Предмет, що лежить. null, якщо id невідомий реєстру. */
    public Item item() {
        String id = this.entityData.get(ITEM_ID);
        if (id.isEmpty()) return null;
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
    }

    public float rotationX() {
        return this.entityData.get(ROTATION_X);
    }

    public boolean hasLanded() {
        return this.entityData.get(LANDED);
    }

    // ── Падіння ──────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (hasLanded()) return; // лежить — рахувати нічого

        if (level().isClientSide) {
            super.tick();
            return;
        }

        fallTicks++;
        setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
        move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().multiply(0.98, 1.0, 0.98));

        boolean timedOut = fallTicks >= ManiacConfigs.get(ConfigSchema.GROUND_ITEM_FALL_MAX_TICKS);
        if (onGround() || timedOut) land();
    }

    /** Фіксує предмет назавжди: далі tick() виходить першим рядком. */
    private void land() {
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        this.entityData.set(LANDED, true);
    }

    // ── Підбирання ───────────────────────────────────────────────────────

    /**
     * Підбирання. Ліміт слотів інвентаря перевіряє модуль виживих —
     * сутність не має знати правил ролей.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;

        double range = ManiacConfigs.get(ConfigSchema.GROUND_ITEM_PICKUP_RANGE);
        if (player.distanceTo(this) > range) return InteractionResult.PASS;

        Item item = item();
        if (item == null) {
            discard();
            return InteractionResult.FAIL;
        }
        if (!player.getInventory().add(new ItemStack(item))) {
            return InteractionResult.FAIL; // інвентар повний — предмет лишається лежати
        }
        discard();
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(ITEM_ID, tag.getString("ItemId"));
        this.entityData.set(ROTATION_X, tag.getFloat("RotationX"));
        this.entityData.set(LANDED, tag.getBoolean("Landed"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ItemId", this.entityData.get(ITEM_ID));
        tag.putFloat("RotationX", this.entityData.get(ROTATION_X));
        tag.putBoolean("Landed", this.entityData.get(LANDED));
    }
}
