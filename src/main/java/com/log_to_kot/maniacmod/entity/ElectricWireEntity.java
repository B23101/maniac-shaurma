package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.traps.ElectricWireArchetype;
import com.log_to_kot.maniacmod.traps.TrapModule;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.UUID;

/**
 * Електричний дріт — «тіло» пастки у світі (ТЗ 2.3).
 *
 * ── Роль сутності ─────────────────────────────────────────────────
 * Одна сутність на ВСЮ пастку: вона знає обидві точки кріплення, має
 * хітбокс уздовж усього дроту, малюється кастомним рендерером
 * (прямий кабель між точками) і володіє обома сутностями-кріпленнями
 * ({@link ElectricWireMountEntity}) — створює їх при появі й прибирає
 * разом із собою. Ігрова логіка (хто торкнувся, удар струмом, таймер)
 * живе в {@link TrapModule} — дріт лише питає модуль у свій тік, як
 * {@link BearTrapEntity}.
 *
 * ── Геометрія ─────────────────────────────────────────────────────
 * Обидві точки — блоки на одному Y; дріт іде ПРЯМО (без провисання,
 * ТЗ 2.1) між їхніми «якорями» на висоті верхньої грані. Та сама
 * функція {@link ElectricWireArchetype#wireAnchor}, що й у валідації
 * розміщення — інакше дозволене місце й намальований дріт розійшлись би.
 *
 * ── Хітбокс: його НЕМАЄ (навмисно) ────────────────────────────────
 * AABB діагонального дроту — великий прямокутник, більша частина якого
 * порожня. Тому сутність має звичайний крихітний бокс у центрі, а
 * всю геометрію рахуємо по ВІДРІЗКУ ({@link com.log_to_kot.maniacmod.traps.WireGeometry}):
 *   • «наступив» — відрізок перетинає AABB гравця ({@code TrapModule.tickWire});
 *   • ножиці — промінь погляду проти відрізка ({@code TrapModule.onScissorsUse}).
 * Великий AABB лишився ЛИШЕ для відсікання рендеру
 * ({@link #getBoundingBoxForCulling()}) — щоб довгий дріт не зникав, коли
 * його центр поза кадром.
 */
public class ElectricWireEntity extends Entity {

    /** Точка кріплення №1 (блок). Синхронізована: клієнт малює дріт за нею. */
    private static final EntityDataAccessor<BlockPos> POINT_A =
        SynchedEntityData.defineId(ElectricWireEntity.class, EntityDataSerializers.BLOCK_POS);

    /** Точка кріплення №2 (блок). */
    private static final EntityDataAccessor<BlockPos> POINT_B =
        SynchedEntityData.defineId(ElectricWireEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final String OWNER_TAG = "TrapOwner";

    private UUID owner;

    /** UUID двох кріплень. Лише сервер; клієнт їх не потребує (малюються самостійно). */
    private UUID mountA;
    private UUID mountB;

    public ElectricWireEntity(EntityType<? extends ElectricWireEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    /**
     * Створює пастку: сам дріт + ДВА кріплення. Викликається з
     * {@link ElectricWireArchetype#spawn}, тобто лише сервером.
     */
    public static ElectricWireEntity spawn(EntityType<ElectricWireEntity> type, Level level,
                                           BlockPos pointA, BlockPos pointB, UUID owner) {
        ElectricWireEntity wire = new ElectricWireEntity(type, level);
        wire.owner = owner;
        wire.entityData.set(POINT_A, pointA);
        wire.entityData.set(POINT_B, pointB);

        Vec3 a = ElectricWireArchetype.wireAnchor(level, pointA);
        Vec3 b = ElectricWireArchetype.wireAnchor(level, pointB);
        Vec3 mid = a.add(b).scale(0.5);
        wire.moveTo(mid.x, mid.y, mid.z, 0f, 0f);
        MatchRuntimeRegistry.register(wire);

        if (level.isClientSide) {
            return wire;
        }
        if (!level.addFreshEntity(wire)) {
            ManiacMod.LOGGER.warn("[trap] електричний дріт не додався у світ між {} і {}", pointA, pointB);
            return null;
        }

        float yawA = yawAlong(a, b);
        float yawB = yawAlong(b, a);
        ElectricWireMountEntity ma = ElectricWireMountEntity.spawn(
            ModEntityTypes.ELECTRIC_WIRE_MOUNT.get(), level, a.x, a.y - ElectricWireArchetype.WIRE_HEIGHT, a.z,
            yawA, wire.getUUID());
        ElectricWireMountEntity mb = ElectricWireMountEntity.spawn(
            ModEntityTypes.ELECTRIC_WIRE_MOUNT.get(), level, b.x, b.y - ElectricWireArchetype.WIRE_HEIGHT, b.z,
            yawB, wire.getUUID());
        if (ma != null) wire.mountA = ma.getUUID();
        if (mb != null) wire.mountB = mb.getUUID();
        return wire;
    }

    /**
     * Точний кут (градуси, 0° = північ/-Z, за годинниковою) погляду від
     * {@code from} до {@code to} у горизонтальній площині. Працює для
     * БУДЬ-ЯКОГО напрямку, зокрема діагоналей — на відміну від старого
     * {@code Direction}, що округлював до 4 сторін.
     */
    static float yawAlong(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        // atan2(dx, -dz): північ (-Z) → 0°, схід (+X) → 90°.
        return (float) Math.toDegrees(Math.atan2(dx, -dz));
    }

    public BlockPos pointA() { return this.entityData.get(POINT_A); }
    public BlockPos pointB() { return this.entityData.get(POINT_B); }
    public UUID owner() { return owner; }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(POINT_A, BlockPos.ZERO);
        this.entityData.define(POINT_B, BlockPos.ZERO);
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
            // Сирота: сутність пережила матч — та сама логіка, що BearTrapEntity.
            discard();
            return;
        }
        traps.tickWire(this);
    }

    // ── Бокс і приціл ────────────────────────────────────────────────────

    /** Кінець №1 дроту у світі (на висоті {@link ElectricWireArchetype#WIRE_HEIGHT}). */
    public Vec3 anchorA() { return ElectricWireArchetype.wireAnchor(level(), pointA()); }

    /** Кінець №2 дроту у світі. */
    public Vec3 anchorB() { return ElectricWireArchetype.wireAnchor(level(), pointB()); }

    /**
     * Бокс ЛИШЕ для відсікання рендеру (frustum culling) і грубого відбору
     * сусідів. Це не хітбокс: ні торкання, ні приціл його не використовують.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        BlockPos a = pointA();
        BlockPos b = pointB();
        if (a.equals(BlockPos.ZERO) && b.equals(BlockPos.ZERO)) {
            return super.getBoundingBoxForCulling();
        }
        return new AABB(anchorA(), anchorB()).inflate(0.3);
    }

    @Override
    public boolean isPickable() {
        // Ванільний приціл сутності не чіпає дріт (був би той самий
        // «великий прямокутник»). Ножиці цілять власним променем.
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;   // ламати дріт можна лише ножицями, не ударами
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 128.0 * 128.0;
    }

    // ── Видалення ────────────────────────────────────────────────────────

    @Override
    public void remove(RemovalReason reason) {
        // Дріт і кріплення — одне ціле: видаляємо кріплення разом із дротом.
        if (!level().isClientSide && level() instanceof net.minecraft.server.level.ServerLevel level) {
            discardMount(level, mountA);
            discardMount(level, mountB);
        }
        super.remove(reason);
    }

    private static void discardMount(net.minecraft.server.level.ServerLevel level, UUID id) {
        if (id == null) return;
        if (level.getEntities().get(id) instanceof ElectricWireMountEntity mount) mount.discard();
    }

    // ── Серіалізація ─────────────────────────────────────────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(POINT_A, BlockPos.of(tag.getLong("PointA")));
        this.entityData.set(POINT_B, BlockPos.of(tag.getLong("PointB")));
        if (tag.hasUUID(OWNER_TAG)) this.owner = tag.getUUID(OWNER_TAG);
        if (tag.hasUUID("MountA")) this.mountA = tag.getUUID("MountA");
        if (tag.hasUUID("MountB")) this.mountB = tag.getUUID("MountB");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("PointA", pointA().asLong());
        tag.putLong("PointB", pointB().asLong());
        if (owner != null) tag.putUUID(OWNER_TAG, owner);
        if (mountA != null) tag.putUUID("MountA", mountA);
        if (mountB != null) tag.putUUID("MountB", mountB);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
