package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.barricade.BarricadeGeometry;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.config.BarricadeConfigs;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Гнучка барикада у світі: ОДНА сутність на всю барикаду.
 *
 * <p>Несе список дощок ({@link BoardSpec}) у синхронізованих даних — клієнт малює їх сам
 * ({@code BarricadeRenderer}), а сервер рахує по них промінь лома. Разом із дошками сутність
 * володіє невидимими колайдерами ({@link BarricadeColliderEntity}), які дають «не пройти».
 *
 * <p>Сутність зберігається в чанк (id + дошки), тому переживає перезапуск. Чи вона ще має
 * існувати, вирішує розмітка {@link BarricadeConfigs}: сирота (барикаду видалили, поки чанк був
 * вивантажений) сама себе прибирає.
 */
public class BarricadeEntity extends Entity {

    private static final EntityDataAccessor<CompoundTag> DATA =
        SynchedEntityData.defineId(BarricadeEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /**
     * Стан цвяхів для візуалу у світі: байт на цвях (індекс = дошка*2 + кінець). 0..95 — наскільки
     * розхитано (кроками по 5%), {@link #NAIL_OUT} — цвях вийшов. Сервер веде міні-гру й лише
     * публікує сюди результат; клієнт за цим малює провислі й упалі дошки. Не зберігається в чанк:
     * прогрес міні-гри теж живе лише в пам'яті сервера.
     */
    private static final EntityDataAccessor<CompoundTag> STATE =
        SynchedEntityData.defineId(BarricadeEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /** Значення стану цвяха «вийшов повністю». */
    public static final byte NAIL_OUT = 101;
    /** Мітка часу «давно» (клієнт): для цвяхів, що вилетіли ще до того, як гравець побачив барикаду. */
    public static final long LONG_AGO = Long.MIN_VALUE / 4;

    private static final int ORPHAN_CHECK_TICKS = 100;

    private String barricadeId = "";
    private final List<UUID> colliders = new ArrayList<>();
    private boolean collidersSpawned;
    private int age;

    // Кеш розібраних дощок: розбираємо тег лише коли прийшов новий.
    private CompoundTag cachedTag;
    private List<BoardSpec> cachedBoards = List.of();
    private AABB cachedBounds;

    private CompoundTag cachedStateTag;
    private byte[] cachedStates = new byte[0];

    // Лише клієнт: коли (gameTime) вилетів кожен цвях і де лежить підлога під кожною дошкою.
    private byte[] visualStates;
    private long[] nailOutAt = new long[0];
    private double[] groundCache = new double[0];

    public BarricadeEntity(EntityType<? extends BarricadeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    /** Створює (ще не додану у світ) сутність барикади. */
    public static BarricadeEntity create(Level level, String id, List<BoardSpec> boards) {
        BarricadeEntity entity = new BarricadeEntity(ModEntityTypes.BARRICADE.get(), level);
        entity.barricadeId = id;
        entity.entityData.set(DATA, BoardSpec.saveWrapped(boards));
        Vec3 c = BarricadeGeometry.centroid(boards);
        entity.setPos(c.x, c.y, c.z);
        return entity;
    }

    public String barricadeId() { return barricadeId; }

    /** Дошки (незмінний список). */
    public List<BoardSpec> boards() {
        CompoundTag tag = entityData.get(DATA);
        if (tag != cachedTag) {
            cachedTag = tag;
            cachedBoards = BoardSpec.loadWrapped(tag);
            cachedBounds = null;
        }
        return cachedBoards;
    }

    /** Відстань до найближчої дошки вздовж променя, або -1. */
    public double rayDistance(Vec3 origin, Vec3 dir, double maxLen) {
        double best = -1;
        List<BoardSpec> all = boards();
        for (int i = 0; i < all.size(); i++) {
            if (isBoardFallen(i)) continue;   // упала дошка лежить на підлозі — в неї не цілимось
            BoardSpec board = all.get(i);
            double d = BarricadeGeometry.rayDistance(board, origin, dir, maxLen);
            if (d >= 0 && (best < 0 || d < best)) best = d;
        }
        return best;
    }

    // ── Стан цвяхів (візуал у світі) ─────────────────────────────────────

    /** Стани всіх цвяхів (порожньо — ще нічого не чіпали). Масив не змінювати. */
    public byte[] nailStates() {
        CompoundTag tag = entityData.get(STATE);
        if (tag != cachedStateTag) {
            cachedStateTag = tag;
            cachedStates = tag.getByteArray("N");
        }
        return cachedStates;
    }

    /** Стан одного цвяха: 0..95 — розхитаність у %, {@link #NAIL_OUT} — вийшов. */
    public int nailState(int nail) {
        byte[] states = nailStates();
        return nail >= 0 && nail < states.length ? states[nail] : 0;
    }

    /** Дошка впала: обидва її цвяхи вийшли. */
    public boolean isBoardFallen(int board) {
        return nailState(board * 2) >= NAIL_OUT && nailState(board * 2 + 1) >= NAIL_OUT;
    }

    /** Скільки дощок уже впало. */
    public int fallenBoards() {
        int count = 0;
        int total = boards().size();
        for (int i = 0; i < total; i++) if (isBoardFallen(i)) count++;
        return count;
    }

    /** Сервер: опублікувати стани цвяхів (нічого не робить, якщо вони не змінились). */
    public void setNailStates(byte[] states) {
        if (Arrays.equals(nailStates(), states)) return;
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("N", states.clone());
        entityData.set(STATE, tag);
    }

    /** Сервер: усі цвяхи знову на місці (новий матч). */
    public void clearNailStates() {
        if (nailStates().length == 0) return;
        entityData.set(STATE, new CompoundTag());
    }

    /**
     * Клієнт: помітити, коли саме вилетів кожен цвях, — з цього рахується анімація провисання й
     * падіння. Цвяхи, що вилетіли ДО того, як гравець побачив барикаду, лежать уже «осілими»
     * (без анімації).
     */
    public void syncVisuals() {
        byte[] now = nailStates();
        if (now == visualStates) return;
        boolean first = visualStates == null;
        long time = level().getGameTime();
        if (nailOutAt.length < now.length) {
            long[] grown = new long[now.length];
            Arrays.fill(grown, LONG_AGO);
            System.arraycopy(nailOutAt, 0, grown, 0, nailOutAt.length);
            nailOutAt = grown;
        }
        for (int i = 0; i < now.length; i++) {
            boolean out = now[i] >= NAIL_OUT;
            boolean was = !first && i < visualStates.length && visualStates[i] >= NAIL_OUT;
            if (out && !was) nailOutAt[i] = first ? LONG_AGO : time;
            else if (!out) nailOutAt[i] = LONG_AGO;
        }
        visualStates = now;
    }

    /** Клієнт: gameTime, коли цей цвях вилетів ({@link #LONG_AGO} — давно або ще ні). */
    public long nailOutAt(int nail) {
        return nail >= 0 && nail < nailOutAt.length ? nailOutAt[nail] : LONG_AGO;
    }

    /** Клієнт: висота підлоги під дошкою (кешується — промінь рахується один раз). */
    public double groundY(int board, Vec3 from) {
        if (groundCache.length <= board) {
            double[] grown = new double[Math.max(board + 1, boards().size())];
            Arrays.fill(grown, Double.NaN);
            System.arraycopy(groundCache, 0, grown, 0, groundCache.length);
            groundCache = grown;
        }
        double ground = groundCache[board];
        if (Double.isNaN(ground)) {
            Vec3 start = from.add(0, 0.05, 0);
            BlockHitResult hit = level().clip(new ClipContext(start, start.add(0, -12, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            ground = hit.getType() == HitResult.Type.MISS ? from.y - 12 : hit.getLocation().y;
            groundCache[board] = ground;
        }
        return ground;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA, new CompoundTag());
        this.entityData.define(STATE, new CompoundTag());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA.equals(accessor)) {
            cachedTag = null;
            groundCache = new double[0];
        }
        if (STATE.equals(accessor)) {
            cachedStateTag = null;
            if (level().isClientSide) syncVisuals();
        }
    }

    // ── Тік (лише сервер) ────────────────────────────────────────────────

    @Override
    public void tick() {
        if (level().isClientSide) return;
        age++;
        if (!collidersSpawned) {
            collidersSpawned = true;
            spawnColliders();
        }
        if (age % ORPHAN_CHECK_TICKS == 0 && BarricadeConfigs.findFlexible(barricadeId) == null) {
            discard();
        }
    }

    private void spawnColliders() {
        double half = BarricadeGeometry.COLLIDER_SIZE / 2.0;
        for (BoardSpec board : boards()) {
            for (Vec3 center : BarricadeGeometry.colliderCenters(board)) {
                BarricadeColliderEntity collider = new BarricadeColliderEntity(
                    ModEntityTypes.BARRICADE_COLLIDER.get(), level());
                // Позиція сутності — центр низу боксу.
                collider.setPos(center.x, center.y - half, center.z);
                if (level().addFreshEntity(collider)) colliders.add(collider.getUUID());
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        // Вивантаження чанка — колайдери зникнуть самі (не зберігаються); знищення — прибираємо.
        if (reason.shouldDestroy() && level() instanceof ServerLevel server) {
            for (UUID id : colliders) {
                Entity e = server.getEntity(id);
                if (e != null) e.discard();
            }
            colliders.clear();
        }
        super.remove(reason);
    }

    // ── Бокс, приціл ─────────────────────────────────────────────────────

    /** Бокс лише для відсікання рендеру: барикада не зникає, коли центр поза кадром. */
    @Override
    public AABB getBoundingBoxForCulling() {
        List<BoardSpec> boards = boards();
        if (boards.isEmpty()) return super.getBoundingBoxForCulling();
        // Упалі дошки лежать на підлозі під барикадою — рамка рендеру має їх охоплювати.
        if (cachedBounds == null) {
            cachedBounds = BarricadeGeometry.bounds(boards).inflate(1.0, 0.0, 1.0).expandTowards(0, -4, 0);
        }
        return cachedBounds;
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 160.0 * 160.0;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        barricadeId = tag.getString("BarricadeId");
        entityData.set(DATA, tag.getCompound("BoardsData"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("BarricadeId", barricadeId);
        tag.put("BoardsData", entityData.get(DATA).copy());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
