package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.core.phase.Phases;
import com.log_to_kot.maniacmod.loot.GroundItemPickup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/**
 * Предмет, що лежить у світі, — і є «предметом» цієї гри.
 *
 * <h2>Що це і чим НЕ є</h2>
 * Це не ванільний {@code ItemEntity}. Різниці, заради яких він існує:
 * <ul>
 *   <li><b>Не зникає.</b> Ванільний предмет видаляється через 5 хвилин
 *       ({@code age}), від вогню, лави, кактуса, вибуху й падіння у
 *       порожнечу. Цей — ні: єдині способи його прибрати —
 *       <em>підбір гравцем</em> і <em>кінець матчу</em>
 *       ({@link MatchRuntimeRegistry#cleanup}).</li>
 *   <li><b>Несе повний {@link ItemStack}</b> (id + NBT). Каністра з
 *       зарядом 37% лежить на землі з зарядом 37% і піднімається з
 *       ним же. Попередня версія зберігала лише {@code Item} — заряд
 *       губився при першому ж викиданні.</li>
 *   <li><b>Має власну фізику</b>, а не «приземлився → заморожений
 *       назавжди»: його можна штовхнути, він котиться по схилу,
 *       падає, якщо вибити блок з-під нього.</li>
 * </ul>
 *
 * <h2>Фізика, яка «спить»</h2>
 * Сотня предметів на карті 300×300 не може щотік рахувати рух. Тому
 * фізика має два стани:
 * <ul>
 *   <li><b>Активна</b> — падає/ковзає; кожен тік {@code move()}.</li>
 *   <li><b>Спить</b> ({@link #SETTLED}) — лежить нерухомо; тік майже
 *       безкоштовний (один лічильник). Прокидається
 *       ({@link #wakeUp()}) коли: його кинули/штовхнули, або раз на
 *       {@link #SUPPORT_RECHECK_TICKS} тіків з'ясувалось, що опори під
 *       ним більше немає.</li>
 * </ul>
 * Перевірка опори — один виклик {@code noCollision} на кілька тіків,
 * а не щотік, тож «прокинутися, коли зник блок» коштує майже нуль.
 *
 * <h2>Порожнеча</h2>
 * Предмет, що впав нижче світу, не зникає («ніколи не помирає») —
 * повертається у {@link #ANCHOR} (позиція першого спавну).
 *
 * <h2>Візуал</h2>
 * Сам нічого не малює — це справа {@code GroundItemRenderer}, який
 * бере {@link #stack()} і показує його як «голову» (HEAD-display).
 * Блиск (білі частинки) — клієнтський, у {@link #tick()} через
 * {@code addParticle}: нуль мережевого трафіку, бачать лише ті, хто
 * поруч.
 *
 * <h2>Підбір</h2>
 * ПКМ (без Shift — Shift+ПКМ зарезервований під генератор). Куди
 * класти — рішає {@link GroundItemPickup}; сутність не знає правил
 * слотів.
 */
public class GroundItemEntity extends Entity {

    /** Що лежить. Повний стек: id + NBT. Синхронізується клієнту. */
    private static final EntityDataAccessor<ItemStack> STACK =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.ITEM_STACK);

    /** Кут повороту по X. Випадковий, задається при спавні. */
    private static final EntityDataAccessor<Float> ROTATION_X =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.FLOAT);

    /** true, коли фізика спить. Клієнту потрібно, щоб не малювати «падіння» на місці. */
    private static final EntityDataAccessor<Boolean> SETTLED =
        SynchedEntityData.defineId(GroundItemEntity.class, EntityDataSerializers.BOOLEAN);

    /** Поворот по Y. Завжди 0 — константа, не синхронізується. */
    public static final float ROTATION_Y = 0f;

    /** Раз на скільки тіків спляча сутність перевіряє, чи є ще опора під нею. */
    private static final int SUPPORT_RECHECK_TICKS = 10;

    /** Швидкість (блок/тік), нижче якої предмет вважається зупиненим. */
    private static final double REST_SPEED_SQR = 1.0E-5;

    /** Тертя по землі. Менше = ковзає далі. Ванільний ItemEntity: 0.98 * 0.6. */
    private static final double GROUND_FRICTION = 0.6 * 0.98;

    /** Опір повітря по горизонталі. */
    private static final double AIR_DRAG = 0.98;

    /** Гравітація, блоків/тік². Те саме значення, що у ванільного ItemEntity. */
    private static final double GRAVITY = 0.04;

    /** Нижче цієї відстані до нижньої межі світу предмет рятується з порожнечі. */
    private static final int VOID_MARGIN_BLOCKS = 16;

    /** Розкид навколо предмета, у якому з'являються блискітки (блоків). */
    private static final double SPARKLE_SPREAD = 0.25;

    /** У середньому раз на скільки тіків з'являється одна блискітка. */
    private static final int SPARKLE_EVERY_TICKS = 6;

    /** Наскільки сильно гравець зсуває предмет одним дотиком, блоків/тік. */
    private static final double PUSH_STRENGTH = 0.02;

    /** Позиція, куди повертаємо предмет із порожнечі. null = ще не задана. */
    private Vec3 anchor;

    /** Скільки тіків лишилось до можливості підбору. Лише сервер. */
    private int pickupDelay = 0;

    /** Лічильник для рідкісних перевірок опори. Лише сервер. */
    private int recheckCounter = 0;

    public GroundItemEntity(EntityType<? extends GroundItemEntity> type, Level level) {
        super(type, level);
        // Ванільний Entity уже «безсмертний» до fire/lava лише через
        // fireImmune() у EntityType; невразливість до уроту — у hurt().
        this.noPhysics = false;
    }

    // ── Створення ────────────────────────────────────────────────────────

    /**
     * Створює предмет, що лежить на карті (початковий спавн).
     *
     * <p><b>Додає у світ.</b> Раніше метод завершувався на
     * {@code MatchRuntimeRegistry.register} — це лише ставить NBT-маркер
     * для очищення, у світ сутність не додає; предмети «спавнились» тільки
     * як Java-об'єкти, яких ніхто не бачив (той самий баг, що вже
     * виправлено в {@code GeneratorEntity.spawn}).</p>
     *
     * @param stack     що покласти. Копіюється — викликач може свій стек
     *                  використати далі.
     * @param rotationX випадковий кут із {@code GroundItemPlacement}
     * @return сутність, або {@code null}, якщо світ її не прийняв
     */
    public static GroundItemEntity spawn(EntityType<GroundItemEntity> type, Level level,
                                         ItemStack stack, double x, double y, double z,
                                         float rotationX) {
        GroundItemEntity entity = create(type, level, stack, x, y, z, rotationX);
        return addToWorld(entity) ? entity : null;
    }

    /**
     * Створює предмет, який гравець щойно КИНУВ (Q). Отримує імпульс
     * так само, як ванільний {@code Player.drop}, — тому падає по тій
     * самій дузі.
     *
     * @param velocity початковий імпульс, блоків/тік
     * @return сутність, або {@code null}, якщо світ її не прийняв
     */
    public static GroundItemEntity thrown(EntityType<GroundItemEntity> type, Level level,
                                          ItemStack stack, Vec3 pos, Vec3 velocity,
                                          float rotationX) {
        GroundItemEntity entity = create(type, level, stack, pos.x, pos.y, pos.z, rotationX);
        entity.setDeltaMovement(velocity);
        entity.pickupDelay = ManiacConfigs.get(ConfigSchema.GROUND_ITEM_DROP_PICKUP_DELAY_TICKS);
        return addToWorld(entity) ? entity : null;
    }

    private static GroundItemEntity create(EntityType<GroundItemEntity> type, Level level,
                                           ItemStack stack, double x, double y, double z,
                                           float rotationX) {
        GroundItemEntity entity = new GroundItemEntity(type, level);
        entity.entityData.set(STACK, stack.copy());
        entity.entityData.set(ROTATION_X, rotationX % 360f);
        entity.moveTo(x, y, z, ROTATION_Y, 0f);
        entity.anchor = new Vec3(x, y, z);
        MatchRuntimeRegistry.register(entity);
        return entity;
    }

    private static boolean addToWorld(GroundItemEntity entity) {
        Level level = entity.level();
        if (level.isClientSide) return true;
        if (level.addFreshEntity(entity)) return true;
        ManiacMod.LOGGER.warn("[ground-item] світ не прийняв сутність на ({}, {}, {})",
            entity.getX(), entity.getY(), entity.getZ());
        return false;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(STACK, ItemStack.EMPTY);
        this.entityData.define(ROTATION_X, 0f);
        this.entityData.define(SETTLED, false);
    }

    /**
     * Що лежить. Повертається КОПІЯ — викликач не може випадково
     * змінити стек сутності в обхід синхронізації.
     */
    public ItemStack stack() {
        return this.entityData.get(STACK).copy();
    }

    /** Чи є що підбирати. Порожній стек = зіпсована/застаріла сутність. */
    public boolean hasStack() {
        return !this.entityData.get(STACK).isEmpty();
    }

    public float rotationX() {
        return this.entityData.get(ROTATION_X);
    }

    /** Чи фізика спить (предмет лежить нерухомо). */
    public boolean isSettled() {
        return this.entityData.get(SETTLED);
    }

    /** Назва для підказки. Береться зі стека — враховує кастомні імена. */
    public Component displayName() {
        return this.entityData.get(STACK).getHoverName();
    }

    // ── Тік ──────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        // Клієнт: базовий тік (xOld/yOld → плавна інтерполяція руху,
        // який приходить від сервера) + блиск. Власної фізики на
        // клієнті немає: позицію веде сервер.
        if (level().isClientSide) {
            super.tick();
            tickClient();
            return;
        }

        if (pickupDelay > 0) pickupDelay--;

        if (isSettled()) {
            tickSleeping();
            return;
        }
        tickPhysics();
    }

    /** Спляча фізика: лише рідка перевірка опори. */
    private void tickSleeping() {
        // Не викликаємо super.tick(): вона рахує портали/воду/вогонь/
        // блоки, тобто робить саме те, чого спляча сутність не повинна
        // коштувати. xOld/yOld при цьому не міняються — позиція теж.
        if (++recheckCounter < SUPPORT_RECHECK_TICKS) return;
        recheckCounter = 0;

        if (!hasSupport()) wakeUp();
    }

    /** Активна фізика: падіння, ковзання, тертя. */
    private void tickPhysics() {
        // Порожнеча — рятуємо, а не даємо зникнути.
        if (getY() < level().getMinBuildHeight() - VOID_MARGIN_BLOCKS) {
            rescueFromVoid();
            return;
        }

        super.tick();

        Vec3 motion = getDeltaMovement();
        if (!isNoGravity()) motion = motion.add(0, -GRAVITY, 0);
        setDeltaMovement(motion);

        move(MoverType.SELF, getDeltaMovement());

        double friction = onGround() ? GROUND_FRICTION : AIR_DRAG;
        Vec3 after = getDeltaMovement();
        setDeltaMovement(after.x * friction, after.y * 0.98, after.z * friction);
        if (onGround() && after.y < 0) {
            setDeltaMovement(getDeltaMovement().multiply(1, -0.5, 1)); // легкий відскок
        }

        // Заснути: на землі й майже не рухається.
        if (onGround() && getDeltaMovement().lengthSqr() < REST_SPEED_SQR) {
            fallAsleep();
        }
    }

    private void fallAsleep() {
        setDeltaMovement(Vec3.ZERO);
        this.entityData.set(SETTLED, true);
        recheckCounter = 0;
        // Ванільний трекер сам розішле фінальну позицію на найближчому
        // апдейті (updateInterval у ModEntityTypes) — окремий ресинк не потрібен.
    }

    /**
     * Прокидає фізику. Публічний: {@code GroundItemHooks} викликає його,
     * коли поруч зламано блок, а сам предмет його не «бачить».
     */
    public void wakeUp() {
        if (level().isClientSide) return;
        this.entityData.set(SETTLED, false);
        recheckCounter = 0;
    }

    /**
     * Чи є опора під предметом. Один виклик {@code noCollision} по
     * тонкому боксу під ногами — значно дешевше повного {@code move()}.
     */
    private boolean hasSupport() {
        var below = getBoundingBox().move(0, -0.05, 0);
        return !level().noCollision(this, below);
    }

    private void rescueFromVoid() {
        Vec3 home = anchor != null ? anchor
            : new Vec3(getX(), level().getMinBuildHeight() + 64, getZ());
        setDeltaMovement(Vec3.ZERO);
        moveTo(home.x, home.y, home.z, ROTATION_Y, 0f);
        this.entityData.set(SETTLED, false);
        ManiacMod.LOGGER.debug("[ground-item] предмет врятовано з порожнечі → {}", home);
    }

    /**
     * Блиск: білі частинки навколо предмета. Лише клієнт.
     *
     * <p>Умикається/вимикається {@code loot.sparkleEnabled} — значення
     * приходить із сервера пакетом {@code GroundItemVisualSettingsPacket}
     * і читається через {@code ClientMatchState}, бо сам конфіг
     * ({@code ManiacConfigs}) на клієнті недоступний.</p>
     */
    private void tickClient() {
        if (!hasStack()) return;
        if (!com.log_to_kot.maniacmod.client.ClientMatchState.groundItemSparkleEnabled()) return;
        if (this.random.nextInt(SPARKLE_EVERY_TICKS) != 0) return;

        double dx = (this.random.nextDouble() - 0.5) * 2 * SPARKLE_SPREAD;
        double dz = (this.random.nextDouble() - 0.5) * 2 * SPARKLE_SPREAD;
        double dy = this.random.nextDouble() * 0.5;
        // END_ROD — маленька біла «іскра», що повільно спливає: саме блиск.
        level().addParticle(ParticleTypes.END_ROD,
            getX() + dx, getY() + 0.1 + dy, getZ() + dz,
            0.0, 0.01, 0.0);
    }

    // ── Підбір ───────────────────────────────────────────────────────────

    /**
     * ПКМ по предмету.
     *
     * <p>Shift+ПКМ пропускаємо — це утримання ремонту генератора
     * ({@code ManiacKeybinds.isRepairHeld}); гравець, що присів і
     * дивиться повз предмет на генератор, не повинен випадково
     * підбирати лут.</p>
     *
     * <p>Усі перевірки повторюються на сервері: клієнт міг бути
     * модифікований.</p>
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (pickupDelay > 0) return InteractionResult.PASS;
        if (!Phases.allows(PhaseRule.ITEM_USE)) return InteractionResult.PASS;

        // Лежачий (CRAWLING) чи непритомний (UNCONSCIOUS) виживий не
        // підбирає предмети: обидва стани — це «руки зайняті власним
        // виживанням» за дизайном (SurvivorState.isCrawlOnly()), той
        // самий принцип, що вже блокує рух і спринт у цих станах.
        MatchOrchestrator match = ManiacMod.match();
        if (match != null && match.isSurvivor(player.getUUID())
            && match.survivorStateOf(player.getUUID()).isCrawlOnly()) {
            return InteractionResult.PASS;
        }

        double range = ManiacConfigs.get(ConfigSchema.GROUND_ITEM_PICKUP_RANGE);
        if (player.distanceTo(this) > range) return InteractionResult.PASS;

        ItemStack stack = this.entityData.get(STACK);
        if (stack.isEmpty()) {
            discard(); // зіпсована сутність — краще прибрати, ніж лишити «привида»
            return InteractionResult.FAIL;
        }

        GroundItemPickup.Result result = GroundItemPickup.place(serverPlayer, stack);
        if (result == GroundItemPickup.Result.NO_FREE_SLOT) {
            serverPlayer.displayClientMessage(
                Component.translatable("maniacmod.hud.ground_item.no_free_slot"), true);
            return InteractionResult.FAIL; // предмет лишається лежати
        }

        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP,
            SoundSource.PLAYERS, 0.3f, 1.0f + this.random.nextFloat() * 0.4f);
        discard();
        return InteractionResult.SUCCESS;
    }

    // ── Безсмертя ────────────────────────────────────────────────────────

    /** Урон ніколи не завдається: ні вогонь, ні лава, ні вибух, ні удар. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    /**
     * Ванільна сутність із {@code isPickable()=true} ловить приціл —
     * саме через це гравець бачить підказку й може тикнути ПКМ.
     */
    @Override
    public boolean isPickable() {
        return true;
    }

    /**
     * Гравці не виштовхують предмет тілом за рахунок ванільного
     * взаємного розштовхування сутностей (те, що штовхає двох гравців
     * одне від одного) — {@code false} вимикає саме це. Легкий
     * поштовх при дотику все одно є, але через {@link #push(Entity)}
     * нижче: дешевший за постійну обробку колізій щотік і будить
     * фізику лише тоді, коли справді торкнулись.
     */
    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * Дотик гравця «прокидає» предмет: слабкий поштовх убік від гравця
     * і пробудження сплячої фізики.
     *
     * <p>Викликається ванільним рушієм колізій незалежно від
     * {@link #isPushable()} (та лише вимикає ВЗАЄМНИЙ штовхач між
     * сутностями, а не цей колбек). Предмет не літає через кімнату —
     * лише трохи зсувається, як і мав би зробити лежачий на підлозі
     * дрібний об'єкт, у який хтось врізався.</p>
     */
    @Override
    public void push(Entity entity) {
        if (level().isClientSide) return;
        if (!(entity instanceof Player)) return;
        if (!hasStack()) return;

        wakeUp();

        Vec3 away = position().subtract(entity.position());
        double horizontalDistSqr = away.x * away.x + away.z * away.z;
        Vec3 nudge = horizontalDistSqr > 1.0E-4
            ? new Vec3(away.x, 0, away.z).normalize().scale(PUSH_STRENGTH)
            : Vec3.ZERO;
        setDeltaMovement(getDeltaMovement().add(nudge.x, 0, nudge.z));
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // Предмети малі; малюємо тільки поблизу, а не на ванільних 64+ блоків.
        return distanceSq < 48.0 * 48.0;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Stack", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            this.entityData.set(STACK, ItemStack.of(tag.getCompound("Stack")));
        }
        this.entityData.set(ROTATION_X, tag.getFloat("RotationX"));
        this.entityData.set(SETTLED, false); // після завантаження чанка — перерахувати опору
        if (tag.contains("AnchorX")) {
            this.anchor = new Vec3(tag.getDouble("AnchorX"),
                tag.getDouble("AnchorY"), tag.getDouble("AnchorZ"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Stack", this.entityData.get(STACK).save(new CompoundTag()));
        tag.putFloat("RotationX", this.entityData.get(ROTATION_X));
        if (anchor != null) {
            tag.putDouble("AnchorX", anchor.x);
            tag.putDouble("AnchorY", anchor.y);
            tag.putDouble("AnchorZ", anchor.z);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
